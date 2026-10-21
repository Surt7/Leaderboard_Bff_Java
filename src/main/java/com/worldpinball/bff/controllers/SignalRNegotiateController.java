package com.worldpinball.bff.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles SignalR negotiation handshake ({@code POST /hubs/lobby/negotiate})
 * required by the Angular {@code @microsoft/signalr} client before WebSocket connection.
 */
@RestController
public class SignalRNegotiateController {

    @PostMapping("/hubs/lobby/negotiate")
    public Mono<ResponseEntity<Map<String, Object>>> negotiate(
            @RequestParam(value = "negotiateVersion", defaultValue = "1") int negotiateVersion) {
        String connectionId = UUID.randomUUID().toString();
        Map<String, Object> response = Map.of(
            "negotiateVersion", negotiateVersion,
            "connectionId", connectionId,
            "availableTransports", List.of(
                Map.of(
                    "transport", "WebSockets",
                    "transferFormats", List.of("Text")
                )
            )
        );
        return Mono.just(ResponseEntity.ok(response));
    }
}
