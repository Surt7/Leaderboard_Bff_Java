package com.worldpinball.bff.controllers;

import com.worldpinball.bff.config.BffSecurityConfiguration;
import com.worldpinball.bff.dto.RegisterPlayerRequest;
import com.worldpinball.bff.dto.RegisterUserResult;
import com.worldpinball.bff.services.IKeycloakAdminService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = AuthController.class)
@Import(BffSecurityConfiguration.class)
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private IKeycloakAdminService keycloakAdminService;

    @Test
    void alive_shouldReturn200Healthy() {
        webTestClient.get()
            .uri("/alive")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("Healthy");
    }

    @Test
    void login_shouldRedirectToOAuth2Keycloak() {
        webTestClient.get()
            .uri("/api/auth/login?returnUrl=/dashboard")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().location("/oauth2/authorization/keycloak");
    }

    @Test
    void getUser_shouldReturn401_whenUnauthenticated() {
        webTestClient.get()
            .uri("/api/auth/user")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    @WithMockUser(username = "pinball_wizard", roles = {"Player", "Admin"})
    void getUser_shouldReturnUserDetails_whenAuthenticated() {
        webTestClient.get()
            .uri("/api/auth/user")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.username").isEqualTo("pinball_wizard")
            .jsonPath("$.roles").value(org.hamcrest.Matchers.hasItems("ROLE_Player", "ROLE_Admin"));
    }

    @Test
    void register_shouldReturn201Created_onSuccess() {
        when(keycloakAdminService.registerUser("JoradMace", "test@domain.com", "Password123!"))
                .thenReturn(Mono.just(new RegisterUserResult.Success("11111111-2222-3333-4444-555555555555")));

        webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPlayerRequest("JoradMace", "test@domain.com", "Password123!"))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/auth/user/11111111-2222-3333-4444-555555555555")
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.userId").isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void register_shouldReturn409Conflict_onUsernameConflict() {
        when(keycloakAdminService.registerUser(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new RegisterUserResult.UsernameConflict("Ce GamerTag est déjà utilisé par un autre joueur.")));

        webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPlayerRequest("JoradMace", "test@domain.com", "Password123!"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Ce GamerTag est déjà utilisé par un autre joueur.")
                .jsonPath("$.field").isEqualTo("username");
    }
}
