package com.umurinan.hikaricp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.umurinan.hikaricp.dto.OrderResponse;
import com.umurinan.hikaricp.dto.PlaceOrderRequest;
import com.umurinan.hikaricp.repository.OrderRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
        "spring.datasource.hikari.minimum-idle=4",
        "spring.datasource.hikari.connection-timeout=2000"
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

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    @Test
    void placeFix_doesNotPinConnectionsDuringExternalCall() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        PlaceOrderRequest request = new PlaceOrderRequest(
                "ada@example.com", List.of("book", "candle"));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger peakActive = sampleActiveDuringLoad(pool);

        long start = System.currentTimeMillis();
        List<CompletableFuture<OrderResponse>> futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> orderService.place(request), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Pricing happens OUTSIDE the transaction. 8 concurrent calls finish in roughly
        // one pricing window (~500ms) instead of serializing on the 4-connection pool.
        assertThat(elapsed).isLessThan(3000);
        assertThat(peakActive.get()).isLessThanOrEqualTo(4);
        assertThat(orderRepository.count()).isEqualTo(8);
    }

    @Test
    void placeAntiPattern_pinsConnectionsAndSerializesUnderLoad() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        PlaceOrderRequest request = new PlaceOrderRequest(
                "ada@example.com", List.of("book"));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger peakPending = samplePendingDuringLoad(pool);

        long start = System.currentTimeMillis();
        List<CompletableFuture<OrderResponse>> futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> orderService.placeAntiPattern(request), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // 8 calls, 500ms pricing each, pool of 4. The pricing call happens INSIDE the
        // transaction, so the pool serializes: ~2 waves of 4 = ~1000ms minimum.
        assertThat(elapsed).isGreaterThan(900);
        // At some point during the load, threads were waiting for connections.
        assertThat(peakPending.get()).isGreaterThan(0);
        assertThat(orderRepository.count()).isEqualTo(8);
    }

    @Test
    void place_persistsPriceFromPricingClient() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                "ada@example.com", List.of("book", "candle", "lamp"));

        OrderResponse response = orderService.place(request);

        // PricingClient.calculate returns items.size() * 9.99
        assertThat(response.price()).isEqualByComparingTo(BigDecimal.valueOf(29.97));
        assertThat(response.customerEmail()).isEqualTo("ada@example.com");
        assertThat(response.items()).containsExactly("book", "candle", "lamp");
        assertThat(response.id()).isNotNull();
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void recent_returnsNewestFirstUpTo20() throws InterruptedException {
        for (int i = 0; i < 25; i++) {
            orderService.place(new PlaceOrderRequest(
                    "buyer-" + i + "@example.com", List.of("widget-" + i)));
            Thread.sleep(5); // ensure distinct created_at values
        }

        List<OrderResponse> recent = orderService.recent();

        assertThat(recent).hasSize(20);
        for (int i = 1; i < recent.size(); i++) {
            assertThat(recent.get(i - 1).createdAt())
                    .isAfterOrEqualTo(recent.get(i).createdAt());
        }
        assertThat(recent.get(0).customerEmail()).isEqualTo("buyer-24@example.com");
    }

    private AtomicInteger sampleActiveDuringLoad(HikariPoolMXBean pool) {
        AtomicInteger peak = new AtomicInteger(0);
        new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                peak.updateAndGet(prev -> Math.max(prev, pool.getActiveConnections()));
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }, "active-sampler").start();
        return peak;
    }

    private AtomicInteger samplePendingDuringLoad(HikariPoolMXBean pool) {
        AtomicInteger peak = new AtomicInteger(0);
        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                peak.updateAndGet(prev -> Math.max(prev, pool.getThreadsAwaitingConnection()));
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }, "pending-sampler").start();
        return peak;
    }
}
