package com.worldpinball.bff.services;

import com.worldpinball.leaderboard.grpc.v1.SessionLobbyResponse;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

public interface ILobbyRelayService {

    Collection<SessionLobbyResponse> getCurrentLobbies();

    void ensureWatchingLobby(UUID sessionId);

    void ensureWatchingAllLobbies();

    Flux<SessionLobbyResponse> getLobbyUpdates(String sessionId);

    Flux<SessionLobbyResponse> getAllLobbyUpdates();
}
