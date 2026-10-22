package com.worldpinball.bff.services;

import com.worldpinball.leaderboard.grpc.v1.GameSessionServiceGrpc;
import com.worldpinball.leaderboard.grpc.v1.SessionLobbyResponse;
import com.worldpinball.leaderboard.grpc.v1.WatchAllLobbiesRequest;
import com.worldpinball.leaderboard.grpc.v1.WatchLobbyRequest;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class LobbyRelayService implements ILobbyRelayService {

    @GrpcClient("gameSessionService")
    private GameSessionServiceGrpc.GameSessionServiceStub grpcStub;

    private final ConcurrentHashMap<String, SessionLobbyResponse> cachedLobbies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> activeSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean isWatchingAll = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Sinks.Many<SessionLobbyResponse> lobbyEventSink =
        Sinks.many().multicast().directBestEffort();

    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
    }

    @Override
    public Collection<SessionLobbyResponse> getCurrentLobbies() {
        return cachedLobbies.values();
    }

    @Override
    public void ensureWatchingLobby(UUID sessionId) {
        if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return;
        }

        log.info("Starting gRPC lobby watch stream for session {}", sessionId);
        WatchLobbyRequest request = WatchLobbyRequest.newBuilder()
            .setSessionId(sessionId.toString())
            .build();

        grpcStub.watchLobby(request, new StreamObserver<>() {
            @Override
            public void onNext(SessionLobbyResponse lobby) {
                cachedLobbies.put(lobby.getSessionId(), lobby);
                lobbyEventSink.tryEmitNext(lobby);
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Lobby stream error for session {}: {}", sessionId, t.getMessage());
                activeSessions.remove(sessionId);
            }

            @Override
            public void onCompleted() {
                log.info("Lobby stream completed for session {}", sessionId);
                activeSessions.remove(sessionId);
            }
        });
    }

    @Override
    public void ensureWatchingAllLobbies() {
        if (!isWatchingAll.compareAndSet(false, true)) {
            return;
        }
        startWatchingAllLobbies();
    }

    private void startWatchingAllLobbies() {
        log.info("Starting gRPC global admin lobby watch stream");
        WatchAllLobbiesRequest request = WatchAllLobbiesRequest.newBuilder().build();

        grpcStub.watchAllLobbies(request, new StreamObserver<>() {
            @Override
            public void onNext(SessionLobbyResponse lobby) {
                log.info("LobbyRelayService: Received lobby from gRPC: {} state={}", lobby.getSessionId(), lobby.getSessionState());
                cachedLobbies.put(lobby.getSessionId(), lobby);
                var emitResult = lobbyEventSink.tryEmitNext(lobby);
                if (emitResult.isFailure()) {
                    log.warn("LobbyRelayService: Failed to emit lobby event to sink: {}", emitResult);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Global admin lobby stream error: {}. Will reconnect in 2s...", t.getMessage());
                scheduler.schedule(() -> {
                    if (isWatchingAll.get()) {
                        startWatchingAllLobbies();
                    }
                }, 2, TimeUnit.SECONDS);
            }

            @Override
            public void onCompleted() {
                log.info("Global admin lobby stream completed. Will reconnect in 2s...");
                scheduler.schedule(() -> {
                    if (isWatchingAll.get()) {
                        startWatchingAllLobbies();
                    }
                }, 2, TimeUnit.SECONDS);
            }
        });
    }

    @Override
    public Flux<SessionLobbyResponse> getLobbyUpdates(String sessionId) {
        return lobbyEventSink.asFlux()
            .filter(lobby -> lobby.getSessionId().equalsIgnoreCase(sessionId));
    }

    @Override
    public Flux<SessionLobbyResponse> getAllLobbyUpdates() {
        return lobbyEventSink.asFlux();
    }
}
