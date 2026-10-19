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
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class LobbyWebSocketHandler implements WebSocketHandler {

    private final ILobbyRelayService relayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Sinks.Many<String> outboundSink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicReference<String> joinedSessionId = new AtomicReference<>(null);

        // Inbound message pipeline
        Mono<Void> inbound = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .flatMap(payload -> handleClientMessage(payload, session, outboundSink, joinedSessionId))
            .doOnError(err -> log.warn("WebSocket inbound error for session {}: {}", session.getId(), err.getMessage()))
            .then();

        // Outbound message pipeline
        Flux<WebSocketMessage> outboundMessages = outboundSink.asFlux()
            .map(session::textMessage);

        Mono<Void> outbound = session.send(outboundMessages);

        return Mono.zip(inbound, outbound).then();
    }

    private Mono<Void> handleClientMessage(String payload,
                                          WebSocketSession session,
                                          Sinks.Many<String> outboundSink,
                                          AtomicReference<String> joinedSessionId) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = root.has("action") ? root.get("action").asText() : "";

            if ("JoinLobby".equalsIgnoreCase(action)) {
                String sessionId = root.has("sessionId") ? root.get("sessionId").asText() : "";
                joinedSessionId.set(sessionId);
                try {
                    UUID uuid = UUID.fromString(sessionId);
                    relayService.ensureWatchingLobby(uuid);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid sessionId format in JoinLobby: {}", sessionId);
                }

                // Subscribe to updates for this session
                relayService.getLobbyUpdates(sessionId)
                    .map(this::toJsonEvent)
                    .doOnNext(outboundSink::tryEmitNext)
                    .takeUntilOther(session.closeStatus())
                    .subscribe();

            } else if ("JoinAdminSupervision".equalsIgnoreCase(action)) {
                relayService.ensureWatchingAllLobbies();

                // Send current snapshots
                for (SessionLobbyResponse cached : relayService.getCurrentLobbies()) {
                    outboundSink.tryEmitNext(toJsonEvent(cached));
                }

                // Stream real-time updates
                relayService.getAllLobbyUpdates()
                    .map(this::toJsonEvent)
                    .doOnNext(outboundSink::tryEmitNext)
                    .takeUntilOther(session.closeStatus())
                    .subscribe();

            } else if ("LeaveLobby".equalsIgnoreCase(action)) {
                joinedSessionId.set(null);
            }
        } catch (Exception e) {
            log.error("Failed to parse WebSocket message: {}", payload, e);
        }

        return Mono.empty();
    }

    private String toJsonEvent(SessionLobbyResponse lobby) {
        try {
            String protoJson = JsonFormat.printer().print(lobby);
            return "{\"event\":\"LobbyStateUpdated\",\"data\":" + protoJson + "}";
        } catch (Exception e) {
            log.error("Failed to serialize proto to json: {}", e.getMessage());
            return "{\"event\":\"LobbyStateUpdated\",\"sessionId\":\"" + lobby.getSessionId() + "\"}";
        }
    }
}
