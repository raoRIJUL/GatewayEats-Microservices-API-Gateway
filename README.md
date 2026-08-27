# GatewayEats — Microservices API Gateway

A Spring Boot microservices system built around an **API Gateway** that handles JWT authentication,
role-based authorization, Redis-backed rate limiting, and Redis response caching — routing to three
backing services (User, Restaurant, Order) that model a food-delivery domain.

This README explains every component of the system and exactly how it works, end to end.

---

## 1. High-Level Architecture

```
                              ┌─────────────────────┐
                              │      Client          │
                              └──────────┬───────────┘
                                         │  HTTP :8080
                                         ▼
                              ┌─────────────────────┐
                              │     API Gateway       │
                              │  (Spring Cloud Gateway)│
                              │                       │
                              │  • JWT Auth/AuthZ     │
                              │  • Rate Limiting      │
                              │  • Response Caching   │
                              │  • Request Routing    │
                              └──────────┬───────────┘
                                         │
                ┌────────────────────────┼────────────────────────┐
                ▼                        ▼                        ▼
      ┌──────────────────┐   ┌──────────────────────┐   ┌──────────────────┐
      │   User Service     │   │  Restaurant Service    │   │  Order Service     │
      │      :8083         │   │       :8081            │   │      :8082         │
      │  (user_db)         │   │  (restaurant_db)       │   │  (order_db)        │
      └──────────────────┘   └──────────────────────┘   └──────────────────┘
                                         ▲                        │
                                         │  internal quote call   │
                                         └────────────────────────┘

      Shared infrastructure: MySQL 8.4 (one instance, 3 isolated databases)
                              Redis 7   (DB0 = rate limiting, DB1 = response cache)
```

Each service is an independent Spring Boot application with its **own database schema** — no service
directly queries another service's database. All cross-service communication happens over HTTP.

---

## 2. API Gateway (`api-gateway`)

The gateway is the single public entry point (port `8080`). Every request passes through it in this
order:

### 2.1 Security layer — JWT Authentication & Authorization (`SecurityConfig.java`)

- Uses Spring Security's **OAuth2 Resource Server** support, configured to validate JWTs signed with a
  shared **HS256** secret (`JwtProperties` binds `JWT_SECRET`, `JWT_ISSUER` from environment variables).
- `NimbusReactiveJwtDecoder` verifies the token's signature and issuer, then extracts the `roles` claim
  and maps it into Spring Security authorities (e.g. `ROLE_CUSTOMER`, `ROLE_RESTAURANT_OWNER`).
- Route-level authorization rules are declared directly in the security filter chain:
  - **Public** (no token needed): `POST /users/register`, `POST /users/login`, `GET /restaurants/**`,
    `GET /items/**`
  - **Authenticated** (any valid role): `GET/PUT /users/**`, `GET /orders/**`
  - **Role-restricted**: `POST /restaurants`, item writes → `RESTAURANT_OWNER`; `POST /orders` →
    `CUSTOMER`; `PATCH /orders/*/status` → `RESTAURANT_OWNER`
  - Anything not explicitly matched is denied by default (`denyAll()`).

### 2.2 Rate Limiting (`RateLimitConfig.java` + `application.yml`)

Implemented using Spring Cloud Gateway's built-in **`RedisRateLimiter`**, which is a **token bucket
algorithm** backed by Redis (DB0) so limits stay consistent even across multiple gateway instances.

Three custom **key resolver** beans decide *who* is being rate-limited:

| Resolver | Key used | Used on |
|---|---|---|
| `clientIpKeyResolver` | Client IP | register, login |
| `publicIpKeyResolver` | Client IP | public restaurant/item reads |
| `userOrIpKeyResolver` | Authenticated user ID, else IP | profile, writes, orders |

Each route defines its own `replenishRate` (tokens/sec), `burstCapacity` (max burst), and
`requestedTokens` (cost per request) — stricter for auth endpoints (bot/brute-force protection),
lenient for public reads, moderate for writes.

### 2.3 Response Caching (`RedisResponseCacheGatewayFilterFactory.java`)

A **custom-written** Gateway filter (not a built-in Spring Cloud Gateway feature) that:

1. Only activates on routes explicitly configured with `RedisResponseCache=<ttl>,<maxEntrySize>`
   — currently only `GET /restaurants/**` and `GET /items/**`.
2. Builds a cache key from the request path + query string.
3. On a **cache hit**: serializes the previously-stored response (status, headers, body) straight back
   to the client — the request never reaches `restaurant-service` or its database.
4. On a **cache miss**: forwards the request downstream, captures the response, serializes it (via
   `CachedHttpResponse` / `CachedHttpResponseCodec`), and stores it in Redis (DB1) with the configured
   TTL (default 30s) and a max entry size guard (default 5MB) to avoid caching oversized payloads.
5. Adds a response header indicating cache hit/miss, useful for debugging and demonstrating the
   behavior.

**Filter order matters**: `RequestRateLimiter` is declared before `RedisResponseCache` in the route's
filter list, so a request is rate-limited *before* the cache is even checked — a cache hit still
consumes a rate-limit token.

### 2.4 Dynamic Routing

Routes are declared in `application.yml`, each mapping a path pattern + HTTP method to a backing
service's internal URL (e.g. `ORDER_SERVICE_URL: http://order-service:8082` inside Docker's network).
Only the gateway is publicly exposed (`ports: 8080:8080` in `compose.yml`); the three backing services
use `expose` only, meaning they're reachable from other containers on the same Docker network but not
from the host machine directly.

### 2.5 Request Logging (`RequestLoggingFilter.java`)

A global filter that logs every request/response (method, path, status, latency) as it passes through
the gateway, giving basic request-level observability.

---

## 3. User Service (`user-service`, port `8083`)

Handles registration, login, and profile management.

- **`UserController`** exposes `POST /users/register`, `POST /users/login`, `GET/PUT /users/{id}`.
- **`UserAccountService`** contains the business logic:
  - Normalizes email (lowercased, trimmed), validates password length (8–72 bytes, since BCrypt has a
    72-byte input limit).
  - **Passwords are hashed with BCrypt** before being persisted — plaintext passwords are never stored.
  - Duplicate email is checked both in application code (`existsByEmail`) *and* enforced at the database
    level via a `UNIQUE` constraint, so a race between two simultaneous registrations can't create two
    accounts with the same email.
- **`JwtTokenService`** issues signed JWTs on successful login (HS256, includes user ID as subject and
  role as a claim, 30-minute TTL by default).
- **Ownership enforcement**: even though the gateway already validated the JWT, `user-service`
  independently re-decodes it and enforces that a user can only read/update **their own** profile
  (`id` in the URL must match the JWT subject) — defense in depth, not just trusting the gateway.
- **`UserAccountRepository`** is a Spring Data JPA repository; methods like `findByEmail(...)` are
  **derived queries** — Hibernate generates the actual SQL from the method name automatically.
- **`DemoUserBootstrap`** optionally seeds demo accounts on startup (toggled via `DEMO_USERS_ENABLED`).

---

## 4. Restaurant Service (`restaurant-service`, port `8081`)

Manages restaurants and their menu items, and provides the **trusted price quote** used by orders.

- **`RestaurantController`** exposes CRUD-style endpoints for restaurants and items, restricted to the
  `RESTAURANT_OWNER` role for writes, public for reads.
- **`RestaurantCatalogService`** contains the business logic for creating restaurants/items and
  fetching catalog data.
- **`POST /internal/restaurants/{id}/quote`** — this endpoint is **not routed through the gateway** and
  is only reachable service-to-service inside the Docker network. Given a list of requested item IDs
  and quantities, it looks up the **current, authoritative price and availability** for each item
  directly from the database and returns a signed quote. This exists specifically so a client can never
  submit a fake/stale price — see Section 6.

---

## 5. Order Service (`order-service`, port `8082`)

Handles order placement, retrieval, and status transitions.

- **`OrderController`** exposes `POST /orders`, `GET /orders/my`, `GET /orders/{id}`,
  `PATCH /orders/{id}/status`.
- **`FoodOrderService`** is where the core business logic lives:
  - **Order creation never trusts client-submitted prices.** It calls `RestaurantCatalogClient.quote(...)`
    (implemented by `RestRestaurantCatalogClient`, a Spring `RestClient` with explicit connect/read
    timeouts) to fetch a fresh quote from `restaurant-service`, then validates that quote thoroughly
    (`validateQuote(...)`: matching restaurant ID, valid owner, correct item count, sane prices/
    quantities) before calculating the order total itself, server-side.
  - **Status transitions are a strict state machine**: `PLACED → CONFIRMED → PREPARING → READY →
    DELIVERED`, enforced via a `Map<OrderStatus, OrderStatus>` lookup — you cannot skip a stage or move
    backward, and only the restaurant owner tied to that order can advance it.
  - **Read access is ownership-scoped**: a customer can only view their own orders; a restaurant owner
    can only view orders placed at their restaurant (`requireCanRead(...)`).
  - If `restaurant-service` is unreachable or errors, it's surfaced as a
    `RestaurantServiceUnavailableException` rather than silently failing or trusting bad data.
- **`FoodOrderRepository`** / **`FoodOrderEntity`** / **`OrderItemEntity`** — standard JPA persistence;
  an order and its line items are saved together as a single aggregate.

---

## 6. Inter-Service Communication — the "Trusted Quote" Flow

This is the most important business-logic flow in the system, and worth understanding end to end:

1. Client sends `POST /orders` with a restaurant ID and a list of `{foodItemId, quantity}` — **no
   prices** are included in the request.
2. Gateway validates JWT, confirms `CUSTOMER` role, applies rate limiting, forwards to `order-service`.
3. `order-service` calls `restaurant-service`'s internal `/internal/restaurants/{id}/quote` endpoint,
   forwarding the caller's `Authorization` header.
4. `restaurant-service` looks up the **real, current** price and availability for each requested item
   directly from `restaurant_db` and returns a quote.
5. `order-service` validates the shape and sanity of that quote, computes the total **itself** from the
   quoted unit prices, and persists the order.

This prevents a classic vulnerability: a malicious client tampering with prices client-side before
submitting an order. The price used is always whatever the restaurant's database says *right now* —
never anything the client provided.

---

## 7. Data Layer

### 7.1 Schema management — Flyway

Each service owns a `src/main/resources/db/migration/V1__create_*_tables.sql` file containing raw DDL
(table definitions, primary keys, unique constraints, foreign keys). On startup, Flyway automatically
runs any migrations that haven't yet been applied to that service's database and records what's been
run in a `flyway_schema_history` table — so schema changes are versioned and reproducible across every
environment, rather than being applied manually.

### 7.2 Data access — Spring Data JPA / Hibernate

Application code never writes raw `INSERT`/`SELECT` statements. Entities (`@Entity` classes) map Java
objects to tables; repositories extend `JpaRepository`, and Hibernate translates method calls
(`save(...)`, `findByEmail(...)`, etc.) into actual SQL at runtime — including **derived queries**,
where the SQL is inferred purely from the repository method's name.

### 7.3 Connection pooling — HikariCP

Each service configures HikariCP (Spring Boot's default JDBC pool) explicitly:
`minimum-idle`, `maximum-pool-size`, and `connection-timeout` are all set in `application.yml`, rather
than left on defaults — keeping a small number of DB connections warm and bounding how many concurrent
connections one service instance can hold.

### 7.4 Database isolation

`infrastructure/mysql/init/01-create-service-databases.sql` creates three separate databases
(`user_db`, `restaurant_db`, `order_db`) on a single MySQL instance and grants one shared application
user (`food_app`) access to all three — but each service is only ever configured to talk to its own
database, enforcing isolation at the application-configuration level.

---

## 8. Caching Layer — Redis

Redis is used for two **logically separate** purposes, split across two Redis logical databases on the
same instance:

- **DB0 — Rate limiting**: token bucket counters, keyed per user/IP per route.
- **DB1 — Response caching**: serialized HTTP responses for `GET /restaurants/**` and `GET /items/**`
  only, with a configurable TTL and max entry size, evicted using an **allkeys-lfu** (least frequently
  used) policy once Redis's configured `maxmemory` limit is reached.

No other endpoints are cached — user profiles and orders are either user-specific or time-sensitive
(order status), so caching them risks serving stale data.

---

## 9. Setting Up Redis

Redis is required for both rate limiting (DB0) and response caching (DB1). To run it locally:

**Option A — Docker (quickest):**
```bash
docker run -d --name gatewayeats-redis -p 6379:6379 redis:7-alpine \
  redis-server --save "" --appendonly no --maxmemory 256mb --maxmemory-policy allkeys-lfu
```

**Option B — Native install:**
```bash
# macOS
brew install redis
redis-server --maxmemory 256mb --maxmemory-policy allkeys-lfu

# Ubuntu/Debian
sudo apt install redis-server
sudo systemctl start redis-server
```

Verify it's running:
```bash
redis-cli ping   # should return PONG
```

**Configuration used by the gateway** (set as environment variables or in `application.yml`):

| Variable | Purpose | Default |
|---|---|---|
| `REDIS_HOST` / `REDIS_PORT` | Rate limiter connection | `localhost` / `6379` |
| `REDIS_DATABASE` | Logical DB for rate limiting | `0` |
| `CACHE_REDIS_HOST` / `CACHE_REDIS_PORT` | Response cache connection | `localhost` / `6379` |
| `CACHE_REDIS_DATABASE` | Logical DB for response cache | `1` |
| `RESTAURANT_CACHE_TTL` | How long cached responses live | `30s` |
| `RESTAURANT_CACHE_MAX_ENTRY_SIZE` | Max size of a single cached response | `5MB` |

Rate limiting and caching use **separate logical databases (0 and 1)** on the same Redis instance, so
their keys never collide, even though both share one running process.

---

## 10. Tech Stack

**Language & Core Framework**
- Java 21
- Spring Boot
- Spring Cloud Gateway (reactive, WebFlux-based)

**Security**
- Spring Security (OAuth2 Resource Server)
- JWT (JJWT / Nimbus JOSE, HS256 signing)
- BCrypt password hashing

**Data Layer**
- MySQL 8.4
- Spring Data JPA / Hibernate
- Flyway (schema migrations)
- HikariCP (connection pooling)

**Caching & Rate Limiting**
- Redis 7
- Spring Cloud Gateway `RedisRateLimiter` (token bucket algorithm)
- Custom Redis-backed response cache filter

**Inter-Service Communication**
- Spring `RestClient` (synchronous HTTP calls between services)

**Build & Tooling**
- Maven (multi-module project)
- JUnit / Spring Boot Test (integration tests per service)
