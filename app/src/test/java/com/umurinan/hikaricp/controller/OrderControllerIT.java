package com.umurinan.hikaricp.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.umurinan.hikaricp.dto.OrderResponse;
import com.umurinan.hikaricp.dto.PlaceOrderRequest;
import com.umurinan.hikaricp.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "pricing.delay-ms=50",
                "spring.datasource.hikari.maximum-pool-size=10",
                "spring.datasource.hikari.minimum-idle=10"
        })
@Testcontainers
class OrderControllerIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    @Test
    void postOrders_returns200AndPersists() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                "buyer@example.com", List.of("a", "b", "c"));

        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "/api/orders", request, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().customerEmail()).isEqualTo("buyer@example.com");
        assertThat(response.getBody().items()).containsExactly("a", "b", "c");
        assertThat(response.getBody().price()).isEqualByComparingTo(BigDecimal.valueOf(29.97));
        assertThat(response.getBody().id()).isNotNull();
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void postOrdersAntiPattern_returns200AndPersistsSameShape() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                "buyer@example.com", List.of("widget"));

        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "/api/orders/anti-pattern", request, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().price()).isEqualByComparingTo(BigDecimal.valueOf(9.99));
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void getRecent_returnsAllPlacedNewestFirst() {
        restTemplate.postForEntity("/api/orders", new PlaceOrderRequest(
                "first@example.com", List.of("x")), OrderResponse.class);
        restTemplate.postForEntity("/api/orders", new PlaceOrderRequest(
                "second@example.com", List.of("x")), OrderResponse.class);
        restTemplate.postForEntity("/api/orders", new PlaceOrderRequest(
                "third@example.com", List.of("x")), OrderResponse.class);

        ResponseEntity<List<OrderResponse>> response = restTemplate.exchange(
                "/api/orders/recent",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<OrderResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3);
        assertThat(response.getBody().get(0).customerEmail()).isEqualTo("third@example.com");
        assertThat(response.getBody().get(2).customerEmail()).isEqualTo("first@example.com");
    }

    @Test
    void getRecent_whenEmpty_returns200WithEmptyList() {
        ResponseEntity<List<OrderResponse>> response = restTemplate.exchange(
                "/api/orders/recent",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<OrderResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void postOrders_withMalformedJson_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{not json", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/orders", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void prometheusEndpoint_exposesHikariMetrics() {
        // Trigger at least one pool acquire so the metrics surface
        restTemplate.postForEntity("/api/orders", new PlaceOrderRequest(
                "metrics@example.com", List.of("x")), OrderResponse.class);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("hikaricp_connections_active");
        assertThat(response.getBody()).contains("hikaricp_connections_idle");
        assertThat(response.getBody()).contains("pool=\"app-hikari\"");
    }
}
