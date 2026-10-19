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
}
