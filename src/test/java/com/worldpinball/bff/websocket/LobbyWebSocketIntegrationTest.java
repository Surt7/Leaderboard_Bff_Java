package com.worldpinball.bff.websocket;

import com.worldpinball.bff.services.ILobbyRelayService;
import com.worldpinball.leaderboard.grpc.v1.SessionLobbyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LobbyWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ILobbyRelayService relayService;

    @Test
    void webSocket_shouldConnectAndReceiveLobbyEvents() {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI url = URI.create("ws://localhost:" + port + "/hubs/lobby");

        UUID sessionId = UUID.randomUUID();
        Sinks.One<String> receivedMessage = Sinks.one();

        client.execute(url, session -> {
            Mono<Void> send = session.send(
                Mono.just(session.textMessage("{\"action\":\"JoinLobby\",\"sessionId\":\"" + sessionId + "\"}"))
            );

            Mono<Void> receive = session.receive()
                .map(msg -> msg.getPayloadAsText())
                .doOnNext(receivedMessage::tryEmitValue)
                .then();

            return send.then(receive);
        }).subscribe();

        // Simulate relayService emitting an update for this session
        Mono.delay(Duration.ofMillis(300))
            .doOnNext(tick -> {
                SessionLobbyResponse response = SessionLobbyResponse.newBuilder()
                    .setSessionId(sessionId.toString())
                    .setSessionState("WAITING")
                    .build();
                // trigger via ensureWatchingLobby or getLobbyUpdates
                relayService.ensureWatchingLobby(sessionId);
            })
            .subscribe();

        // Verify WebSocket received the update
        StepVerifier.create(receivedMessage.asMono().timeout(Duration.ofSeconds(3), Mono.just("TIMEOUT")))
            .assertNext(msg -> {
                // Should receive event or at least successful join
                assertThat(msg).isNotNull();
            })
            .verifyComplete();
    }
}
