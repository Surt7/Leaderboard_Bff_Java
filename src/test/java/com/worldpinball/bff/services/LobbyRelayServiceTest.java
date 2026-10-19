package com.worldpinball.bff.services;

import com.worldpinball.leaderboard.grpc.v1.GameSessionServiceGrpc;
import com.worldpinball.leaderboard.grpc.v1.SessionLobbyResponse;
import com.worldpinball.leaderboard.grpc.v1.WatchLobbyRequest;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LobbyRelayServiceTest {

    @Mock
    private GameSessionServiceGrpc.GameSessionServiceStub grpcStub;

    @InjectMocks
    private LobbyRelayService relayService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(relayService, "grpcStub", grpcStub);
    }

    @Test
    void ensureWatchingLobby_shouldInvokeGrpcWatchLobbyAndBroadcastUpdates() {
        UUID sessionId = UUID.randomUUID();

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamObserver<SessionLobbyResponse> observer = invocation.getArgument(1);
            SessionLobbyResponse response = SessionLobbyResponse.newBuilder()
                .setSessionId(sessionId.toString())
                .setSessionState("WAITING")
                .build();
            observer.onNext(response);
            return null;
        }).when(grpcStub).watchLobby(any(WatchLobbyRequest.class), any());

        relayService.ensureWatchingLobby(sessionId);

        // Verification of cached lobby
        assertThat(relayService.getCurrentLobbies()).hasSize(1);
        SessionLobbyResponse cached = relayService.getCurrentLobbies().iterator().next();
        assertThat(cached.getSessionId()).isEqualTo(sessionId.toString());
        assertThat(cached.getSessionState()).isEqualTo("WAITING");

        ArgumentCaptor<WatchLobbyRequest> captor = ArgumentCaptor.forClass(WatchLobbyRequest.class);
        verify(grpcStub).watchLobby(captor.capture(), any());
        assertThat(captor.getValue().getSessionId()).isEqualTo(sessionId.toString());
    }

    @Test
    void ensureWatchingLobby_shouldNotDuplicateStreams_whenCalledTwice() {
        UUID sessionId = UUID.randomUUID();

        relayService.ensureWatchingLobby(sessionId);
        relayService.ensureWatchingLobby(sessionId);

        verify(grpcStub, times(1)).watchLobby(any(WatchLobbyRequest.class), any());
    }
}
