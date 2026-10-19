package com.worldpinball.bff;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BffGatewayIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoads_andAliveEndpointResponds() {
        webTestClient.get()
            .uri("/alive")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("Healthy");
    }

    @Test
    void gatewayRoutes_shouldContainCoreApiRoute() {
        var routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();
        assertThat(routes.stream().anyMatch(r -> "core-api-route".equals(r.getId()))).isTrue();
    }

    @Test
    void securityHeaders_shouldBePresentOnResponses() {
        webTestClient.get()
            .uri("/alive")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader().valueEquals("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none';")
            .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin")
            .expectHeader().valueEquals("Permissions-Policy", "accelerometer=(), camera=(), geolocation=(), microphone=()");
    }

    @Autowired
    private org.springframework.web.server.session.WebSessionIdResolver webSessionIdResolver;

    @Test
    void webSessionIdResolver_shouldConfigureHostPrefixAndAttributes() {
        assertThat(webSessionIdResolver).isInstanceOf(org.springframework.web.server.session.CookieWebSessionIdResolver.class);
        var resolver = (org.springframework.web.server.session.CookieWebSessionIdResolver) webSessionIdResolver;
        org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/alive").build();
        org.springframework.mock.web.server.MockServerWebExchange exchange =
            org.springframework.mock.web.server.MockServerWebExchange.from(request);
        
        resolver.setSessionId(exchange, "test-session-123");
        var cookie = exchange.getResponse().getCookies().getFirst("__Host-worldpinball-session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("test-session-123");
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
    }
}
