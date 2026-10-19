package com.worldpinball.bff.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.worldpinball.bff.config.BffSecurityConfiguration;

@WebFluxTest(controllers = AuthController.class)
@Import(BffSecurityConfiguration.class)
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

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
}
