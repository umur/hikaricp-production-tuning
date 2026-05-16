# HikariCP in Production: Tuning, Monitoring, and pgbouncer

Companion code for the tutorial [HikariCP in Production: Tuning, Monitoring, and pgbouncer](https://umurinan.com/pages/tutorials/hikaricp-production-tuning.html).

A working Spring Boot 4 + PostgreSQL 17 + pgbouncer + Prometheus + Grafana stack you can run locally to see HikariCP pool exhaustion happen in real time. Includes a k6 load script that drives the pool, a Prometheus alert rule that fires on saturation, and a deliberate `@Transactional` anti-pattern alongside the fix so you can A/B the two under load.

---

## What is in this repo

```
hikaricp-production-tuning/
├── app/                        Spring Boot 4 service (Java 21)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/umurinan/hikaricp/
│       │   ├── controller/OrderController.java
│       │   ├── service/OrderService.java        place() vs placeAntiPattern()
│       │   ├── client/PricingClient.java        slow external call sim
│       │   ├── entity/OrderEntity.java
│       │   ├── repository/OrderRepository.java
│       │   └── dto/PlaceOrderRequest.java OrderResponse.java
│       ├── main/resources/
│       │   ├── application.yml                  full HikariCP config
│       │   └── db/migration/V1__create_orders.sql
│       └── test/java/com/umurinan/hikaricp/
│           └── service/OrderServiceIT.java      Testcontainers, asserts pool not pinned
├── docker-compose.yml          Postgres + pgbouncer + app + Prometheus + Grafana
├── prometheus/
│   ├── prometheus.yml
│   └── alerts.yml              HikariPoolSaturated + 2 supporting alerts
├── loadtest.js                 k6 spike script (50 VUs for 2 min)
└── README.md                   you are here
```

All container names are prefixed `hikaricp-demo-` so the stack does not collide with anything else on the same Docker host.

---

## The story this repo tells

`OrderService` has two ways to create an order:

```java
@Transactional
public OrderResponse placeAntiPattern(PlaceOrderRequest request) {
    OrderEntity entity = orderRepository.save(toEntity(request, BigDecimal.ZERO));
    BigDecimal price = pricingClient.calculate(request.items());   // 2-second sleep
    entity.setPrice(price);
    return OrderResponse.from(entity);
}

public OrderResponse place(PlaceOrderRequest request) {
    BigDecimal price = pricingClient.calculate(request.items());   // outside @Transactional
    return persist(request, price);
}
```

`PricingClient` sleeps for 2 seconds to simulate a slow external pricing service. In the anti-pattern, the database connection is held for the full 2 seconds. In the fix, the transaction is open only for the database write.

Run k6 against both endpoints with `HIKARI_MAX_POOL_SIZE=10` and watch what happens.

---

## Run it locally

Requirements: Docker, Docker Compose, `mvn` (for the integration test), and optionally `k6` for the load test.

```bash
# bring up the whole stack
docker compose up --build

# in another terminal: drive the fixed endpoint (no saturation)
k6 run loadtest.js

# drive the anti-pattern endpoint (pool exhausts within seconds)
k6 run -e ENDPOINT=anti-pattern loadtest.js
```

Watch:

- **Grafana** at `http://localhost:3000` (admin / admin). Add Prometheus as a data source (URL `http://hikaricp-demo-prometheus:9090`) and graph `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_idle`.
- **Prometheus alerts** at `http://localhost:9090/alerts`. `HikariPoolSaturated` fires within 30 seconds of starting the anti-pattern load test.
- **Raw metrics** at `http://localhost:8080/actuator/prometheus`.

Stop everything: `docker compose down -v`.

---

## Run the integration test

```bash
cd app
mvn verify
```

`OrderServiceIT` spins up a real PostgreSQL via Testcontainers, sets `maximum-pool-size=4` and 8 concurrent requests against the FIXED endpoint, asserts that mid-flight active connections stay at or below 4 (because pricing runs outside the transaction) and that all 8 calls finish well under 3 seconds. The same test against the anti-pattern endpoint would take roughly 4 seconds and pin all 4 connections.

---

## Tuning starter values

The `application.yml` defaults are:

| Property | Value | Why |
|---|---|---|
| `maximum-pool-size` | 10 (override via `HIKARI_MAX_POOL_SIZE`) | small on purpose so saturation happens fast |
| `minimum-idle` | same as max | avoid latency cliff on traffic spikes |
| `connection-timeout` | 5000ms | fail fast and shed load |
| `max-lifetime` | 1800000ms | smaller than any middlebox timeout |
| `keepalive-time` | 60000ms | catch NAT-killed idle connections |
| `leak-detection-threshold` | 5000ms | log stack trace when a connection is held over 5s |
| `prepareThreshold=0` (JDBC URL) | required for pgbouncer transaction pooling |

See the [tutorial](https://umurinan.com/pages/tutorials/hikaricp-production-tuning.html) for the reasoning behind each value.

---

## Tech stack

- Java 21
- Spring Boot 4.0.6
- PostgreSQL 17
- pgbouncer (transaction pooling mode)
- Flyway (schema migration)
- HikariCP 6.x (Spring Boot default)
- Micrometer + Prometheus + Grafana
- Testcontainers (integration test)
- k6 (load generation)
