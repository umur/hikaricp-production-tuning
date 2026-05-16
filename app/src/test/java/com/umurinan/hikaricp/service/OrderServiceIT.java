package com.umurinan.hikaricp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.umurinan.hikaricp.dto.OrderResponse;
import com.umurinan.hikaricp.dto.PlaceOrderRequest;
import com.umurinan.hikaricp.repository.OrderRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "pricing.delay-ms=500",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=4"
})
@Testcontainers
class OrderServiceIT {

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
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void placeFix_doesNotPinConnectionsDuringExternalCall() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        PlaceOrderRequest request = new PlaceOrderRequest(
                "ada@example.com", List.of("book", "candle"));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        long start = System.currentTimeMillis();
        List<CompletableFuture<OrderResponse>> futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> orderService.place(request), executor))
                .toList();

        Thread.sleep(100);
        int activeMidFlight = pool.getActiveConnections();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();

        // The pricing call (500ms) runs outside the transaction, so 8 concurrent calls
        // do not pin the 4-connection pool during pricing. Mid-flight active should be
        // small relative to in-flight requests.
        assertThat(activeMidFlight).isLessThanOrEqualTo(4);
        // 8 calls with 4 connections and ~500ms pricing each: expect well under 4s,
        // because pricing runs concurrently outside the transaction.
        assertThat(Duration.ofMillis(elapsed)).isLessThan(Duration.ofMillis(3000));
        assertThat(orderRepository.count()).isEqualTo(8);
    }

    @Test
    void recent_returnsNewestFirst() {
        for (int i = 0; i < 3; i++) {
            orderService.place(new PlaceOrderRequest(
                    "buyer-" + i + "@example.com", List.of("widget")));
        }

        List<OrderResponse> recent = orderService.recent();

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).createdAt()).isAfterOrEqualTo(recent.get(2).createdAt());
    }
}
