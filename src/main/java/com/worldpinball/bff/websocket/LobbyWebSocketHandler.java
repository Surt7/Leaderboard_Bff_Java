package com.worldpinball.bff.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.worldpinball.bff.services.ILobbyRelayService;
import com.worldpinball.leaderboard.grpc.v1.SessionLobbyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class LobbyWebSocketHandler implements WebSocketHandler {

    private static final String RECORD_SEPARATOR = "\u001e";

    private final ILobbyRelayService relayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Sinks.Many<String> outboundSink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicReference<String> joinedSessionId = new AtomicReference<>(null);
        AtomicBoolean isSignalR = new AtomicBoolean(false);

        // Inbound message pipeline
        Mono<Void> inbound = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .flatMap(payload -> handleIncomingText(payload, session, outboundSink, joinedSessionId, isSignalR))
            .doOnError(err -> log.warn("WebSocket inbound error for session {}: {}", session.getId(), err.getMessage()))
            .then();

        // Outbound message pipeline
        Flux<WebSocketMessage> outboundMessages = outboundSink.asFlux()
            .map(session::textMessage);

        Mono<Void> outbound = session.send(outboundMessages);

        return Mono.zip(inbound, outbound).then();
    }

    private Mono<Void> handleIncomingText(String text,
                                         WebSocketSession session,
                                         Sinks.Many<String> outboundSink,
                                         AtomicReference<String> joinedSessionId,
                                         AtomicBoolean isSignalR) {
        String[] parts = text.split(RECORD_SEPARATOR);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            handleSingleMessage(trimmed, session, outboundSink, joinedSessionId, isSignalR);
        }
        return Mono.empty();
    }

    private void handleSingleMessage(String payload,
                                     WebSocketSession session,
                                     Sinks.Many<String> outboundSink,
                                     AtomicReference<String> joinedSessionId,
                                     AtomicBoolean isSignalR) {
        try {
            JsonNode root = objectMapper.readTree(payload);

            // 1. SignalR Handshake
            if (root.has("protocol") && "json".equalsIgnoreCase(root.get("protocol").asText())) {
                isSignalR.set(true);
                log.info("SignalR handshake completed for session {}", session.getId());
                outboundSink.tryEmitNext("{}" + RECORD_SEPARATOR);
                return;
            }

            // 2. SignalR Ping (type 6)
            if (root.has("type") && root.get("type").asInt() == 6) {
                outboundSink.tryEmitNext("{\"type\":6}" + RECORD_SEPARATOR);
                return;
            }

            // 3. SignalR Invocation (type 1)
            if (root.has("type") && root.get("type").asInt() == 1) {
                isSignalR.set(true);
                String target = root.has("target") ? root.get("target").asText() : "";
                String invocationId = root.has("invocationId") ? root.get("invocationId").asText() : null;
                JsonNode args = root.get("arguments");

                if ("JoinLobby".equalsIgnoreCase(target)) {
                    String sessionId = (args != null && args.size() > 0) ? args.get(0).asText() : "";
                    joinedSessionId.set(sessionId);
                    try {
                        UUID uuid = UUID.fromString(sessionId);
                        relayService.ensureWatchingLobby(uuid);
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid sessionId format in JoinLobby: {}", sessionId);
                    }

                    relayService.getCurrentLobbies().stream()
                        .filter(l -> sessionId.equals(l.getSessionId()))
                        .findFirst()
                        .ifPresent(cached -> outboundSink.tryEmitNext(toSignalREvent(cached, isSignalR.get())));

                    relayService.getLobbyUpdates(sessionId)
                        .map(lobby -> toSignalREvent(lobby, isSignalR.get()))
                        .doOnNext(outboundSink::tryEmitNext)
                        .takeUntilOther(session.closeStatus())
                        .subscribe();

                    if (invocationId != null) {
                        outboundSink.tryEmitNext("{\"type\":3,\"invocationId\":\"" + invocationId + "\"}" + RECORD_SEPARATOR);
                    }
                } else if ("JoinAdminSupervision".equalsIgnoreCase(target)) {
                    relayService.ensureWatchingAllLobbies();

                    for (SessionLobbyResponse cached : relayService.getCurrentLobbies()) {
                        outboundSink.tryEmitNext(toSignalREvent(cached, isSignalR.get()));
                    }

                    relayService.getAllLobbyUpdates()
                        .map(lobby -> toSignalREvent(lobby, isSignalR.get()))
                        .doOnNext(msg -> {
                            log.info("LobbyWebSocketHandler: Pushing live update to WebSocket session {}", session.getId());
                            outboundSink.tryEmitNext(msg);
                        })
                        .takeUntilOther(session.closeStatus())
                        .subscribe();

                    if (invocationId != null) {
                        outboundSink.tryEmitNext("{\"type\":3,\"invocationId\":\"" + invocationId + "\"}" + RECORD_SEPARATOR);
                    }
                } else if ("LeaveLobby".equalsIgnoreCase(target)) {
                    joinedSessionId.set(null);
                    if (invocationId != null) {
                        outboundSink.tryEmitNext("{\"type\":3,\"invocationId\":\"" + invocationId + "\"}" + RECORD_SEPARATOR);
                    }
                } else if ("LeaveAdminSupervision".equalsIgnoreCase(target)) {
                    if (invocationId != null) {
                        outboundSink.tryEmitNext("{\"type\":3,\"invocationId\":\"" + invocationId + "\"}" + RECORD_SEPARATOR);
                    }
                }
                return;
            }

            // 4. Raw WebSocket Fallback (action: JoinLobby, JoinAdminSupervision)
            String action = root.has("action") ? root.get("action").asText() : "";
            if ("JoinLobby".equalsIgnoreCase(action)) {
                String sessionId = root.has("sessionId") ? root.get("sessionId").asText() : "";
                joinedSessionId.set(sessionId);
                try {
                    UUID uuid = UUID.fromString(sessionId);
                    relayService.ensureWatchingLobby(uuid);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid sessionId format: {}", sessionId);
                }

                relayService.getCurrentLobbies().stream()
                    .filter(l -> sessionId.equals(l.getSessionId()))
                    .findFirst()
                    .ifPresent(cached -> outboundSink.tryEmitNext(toSignalREvent(cached, false)));

                relayService.getLobbyUpdates(sessionId)
                    .map(lobby -> toSignalREvent(lobby, false))
                    .doOnNext(outboundSink::tryEmitNext)
                    .takeUntilOther(session.closeStatus())
                    .subscribe();

            } else if ("JoinAdminSupervision".equalsIgnoreCase(action)) {
                relayService.ensureWatchingAllLobbies();
                for (SessionLobbyResponse cached : relayService.getCurrentLobbies()) {
                    outboundSink.tryEmitNext(toSignalREvent(cached, false));
                }
                relayService.getAllLobbyUpdates()
                    .map(lobby -> toSignalREvent(lobby, false))
                    .doOnNext(outboundSink::tryEmitNext)
                    .takeUntilOther(session.closeStatus())
                    .subscribe();

            } else if ("LeaveLobby".equalsIgnoreCase(action)) {
                joinedSessionId.set(null);
            }
        } catch (Exception e) {
            log.error("Failed to parse WebSocket message: {}", payload, e);
        }
    }

    private String toSignalREvent(SessionLobbyResponse lobby, boolean signalR) {
        try {
            String protoJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .printingEnumsAsInts()
                .print(lobby);
            if (signalR) {
                return "{\"type\":1,\"target\":\"LobbyStateUpdated\",\"arguments\":[" + protoJson + "]}" + RECORD_SEPARATOR;
            }
            return "{\"event\":\"LobbyStateUpdated\",\"data\":" + protoJson + "}";
        } catch (Exception e) {
            log.error("Failed to serialize proto to json: {}", e.getMessage());
            if (signalR) {
                return "{\"type\":1,\"target\":\"LobbyStateUpdated\",\"arguments\":[{\"sessionId\":\"" + lobby.getSessionId() + "\"}]}" + RECORD_SEPARATOR;
            }
            return "{\"event\":\"LobbyStateUpdated\",\"sessionId\":\"" + lobby.getSessionId() + "\"}";
        }
    }
}
