# Food Delivery API Gateway

This project contains four Spring Boot application services:

```text
Client
  |
  v
API Gateway :8080
  |-- JWT validation and role authorization
  |-- Redis rate limiting
  |-- Redis response caching
  `-- Routing
        |
        |-- User Service :8083 ------> user_db
        |-- Restaurant Service :8081 -> restaurant_db
        `-- Order Service :8082 -----> order_db

MySQL :3306 contains the three isolated schemas.
Redis :6379 uses database 0 for rate limits and database 1 for response caching.
```

Only the API Gateway is published as an application port. The downstream services are private
inside the Compose network.

## Responsibilities

| Application service | Responsibility |
|---|---|
| API Gateway | JWT verification, coarse role authorization, routing, Redis rate limits, restaurant response caching, logs, and metrics |
| User Service | Registration, BCrypt login, profile access, user JPA persistence, and JWT creation |
| Restaurant Service | Restaurant/menu JPA persistence, owner checks, public catalog reads, and trusted price quotes |
| Order Service | Trusted price lookup, total calculation, order/item JPA persistence, access checks, and status transitions |

Each downstream service validates the JWT again before applying its domain-level ownership rules.
No business service reads another service's database.

## Run locally

Prerequisites: Docker Desktop and Docker Compose.

```powershell
docker compose up --build
```

Health check:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

The `food-mysql-data` volume preserves all three databases across restarts:

```powershell
docker compose down
```

To intentionally remove all project data:

```powershell
docker compose down -v
```

Accounts and data from the previous auth/product project are not automatically migrated.

## User Service

### Register

```http
POST /users/register
```

```json
{
  "name": "Rijul Kumar",
  "email": "rijul@example.com",
  "password": "strong-password",
  "role": "CUSTOMER"
}
```

Valid roles are `CUSTOMER` and `RESTAURANT_OWNER`. Passwords are stored only as BCrypt hashes.
Email addresses are normalized to lowercase and have a database unique constraint.

### Login

```http
POST /users/login
```

```json
{
  "email": "rijul@example.com",
  "password": "strong-password"
}
```

Example response:

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

The JWT subject is the immutable numeric user ID. Its `roles` claim contains either `CUSTOMER`
or `RESTAURANT_OWNER`.

### Profile

```http
GET /users/{id}
PUT /users/{id}
```

A user can read or update only the profile whose ID equals the JWT subject. Password hashes are
never returned by the API.

Demo users are inserted into `user_db` when `DEMO_USERS_ENABLED=true`:

| Email | Password | Role |
|---|---|---|
| `customer@demo.com` | `customer-password` | `CUSTOMER` |
| `owner@demo.com` | `owner-password` | `RESTAURANT_OWNER` |

## Restaurant Service

| Method and path | Access |
|---|---|
| `POST /restaurants` | `RESTAURANT_OWNER` |
| `GET /restaurants` | Public |
| `GET /restaurants/{id}` | Public |
| `POST /restaurants/{id}/items` | Owner of that restaurant |
| `GET /restaurants/{id}/items` | Public |
| `PUT /items/{id}` | Owner of that restaurant |
| `DELETE /items/{id}` | Owner of that restaurant |

Restaurant rows store `owner_user_id`, taken from the JWT rather than the request body. The
gateway checks the role and the Restaurant Service checks ownership against its database.

Food prices use `BigDecimal`/`DECIMAL(12,2)`. The internal endpoint
`POST /internal/restaurants/{id}/quote` is not routed publicly; the Order Service uses it to
validate restaurant activity, item ownership, availability, names, and current prices.

## Restaurant caching

Public restaurant and menu GET requests use the gateway's Redis response-cache filter:

```text
GET /restaurants/10
  -> Gateway IP rate limit
  -> Redis database 1 lookup
       |-- HIT  -> cached response
       `-- MISS -> Restaurant Service -> restaurant_db -> cache response
```

Responses include `X-Gateway-Cache: HIT` or `X-Gateway-Cache: MISS`. Defaults:

- TTL: 30 seconds.
- Maximum cacheable response: 5 MB.
- Redis memory limit: 256 MB with LFU eviction.
- Oversized responses are streamed but not cached.
- Cache failures fall through to the Restaurant Service.

Writes can therefore be followed by stale GET responses for at most the configured short TTL.
Explicit cache invalidation can be added later if immediate consistency is required.

## Order Service

| Method and path | Access |
|---|---|
| `POST /orders` | `CUSTOMER` |
| `GET /orders/my` | Authenticated customer or owner |
| `GET /orders/{id}` | The customer or restaurant owner for that order |
| `PATCH /orders/{id}/status` | The restaurant owner for that order |

Create request:

```json
{
  "restaurantId": 10,
  "items": [
    { "foodItemId": 101, "quantity": 2 }
  ]
}
```

The request does not accept `userId`, prices, or `totalAmount`. The Order Service derives the user
ID from the JWT, obtains a trusted quote from the Restaurant Service, calculates each line total
and the order total, then stores price/name snapshots in `order_items`.

Only the next status is accepted:

```text
PLACED -> CONFIRMED -> PREPARING -> READY -> DELIVERED
```

Skipping or reversing a status returns HTTP `400`. Updating another owner's order returns `403`.

## Complete example

Register and log in as a restaurant owner:

```powershell
$ownerRegistration = @{
  name = "Demo Owner"
  email = "new-owner@example.com"
  password = "owner-password"
  role = "RESTAURANT_OWNER"
} | ConvertTo-Json
$owner = Invoke-RestMethod -Method Post -Uri http://localhost:8080/users/register `
  -ContentType application/json -Body $ownerRegistration
$ownerLogin = @{ email = "new-owner@example.com"; password = "owner-password" } | ConvertTo-Json
$ownerToken = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/users/login `
  -ContentType application/json -Body $ownerLogin).accessToken
$ownerHeaders = @{ Authorization = "Bearer $ownerToken" }
```

Create a restaurant and menu item:

```powershell
$restaurantBody = @{ name = "Spice House"; address = "Main Street" } | ConvertTo-Json
$restaurant = Invoke-RestMethod -Method Post -Uri http://localhost:8080/restaurants `
  -Headers $ownerHeaders -ContentType application/json -Body $restaurantBody
$itemBody = @{
  name = "Paneer Bowl"
  description = "Paneer, rice and vegetables"
  price = 12.50
  available = $true
} | ConvertTo-Json
$item = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/restaurants/$($restaurant.id)/items" `
  -Headers $ownerHeaders -ContentType application/json -Body $itemBody
```

Register a customer, log in, and place an order:

```powershell
$customerRegistration = @{
  name = "Demo Customer Two"
  email = "new-customer@example.com"
  password = "customer-password"
  role = "CUSTOMER"
} | ConvertTo-Json
$customer = Invoke-RestMethod -Method Post -Uri http://localhost:8080/users/register `
  -ContentType application/json -Body $customerRegistration
$customerLogin = @{ email = "new-customer@example.com"; password = "customer-password" } | ConvertTo-Json
$customerToken = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/users/login `
  -ContentType application/json -Body $customerLogin).accessToken
$customerHeaders = @{ Authorization = "Bearer $customerToken" }
$orderBody = @{
  restaurantId = $restaurant.id
  items = @(@{ foodItemId = $item.id; quantity = 2 })
} | ConvertTo-Json -Depth 4
$order = Invoke-RestMethod -Method Post -Uri http://localhost:8080/orders `
  -Headers $customerHeaders -ContentType application/json -Body $orderBody
```

Confirm the order as its restaurant owner:

```powershell
$statusBody = @{ status = "CONFIRMED" } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "http://localhost:8080/orders/$($order.id)/status" `
  -Headers $ownerHeaders -ContentType application/json -Body $statusBody
```

## Rate limits

| Route | Key | Refill | Burst |
|---|---|---:|---:|
| `POST /users/register` | Client IP | 1/second | 3 |
| `POST /users/login` | Client IP | 1/second | 5 |
| User profile writes/reads | JWT user ID | 3/second | 6 |
| Public restaurant reads | Client IP | 10/second | 20 |
| Restaurant writes | JWT user ID | 3/second | 6 |
| Order APIs | JWT user ID | 2/second | 4 |

Redis database 0 stores the shared token buckets, so multiple gateway instances enforce the same
limits. Excess requests return HTTP `429 Too Many Requests`.

## Persistence and migrations

Each service owns its JPA entities and Flyway migration:

- `user-service`: `users` in `user_db`.
- `restaurant-service`: `restaurants`, `food_items` in `restaurant_db`.
- `order-service`: `food_orders`, `order_items` in `order_db`.

Flyway creates the tables and Hibernate uses `ddl-auto=validate`. HikariCP reuses database
connections with a default maximum pool size of 10 per service instance.

## Build and tests

With Java 21 and Maven installed:

```powershell
mvn clean test
mvn clean package
```

Integration tests cover registration/login/JWT issuance, self-only user access, restaurant
ownership and trusted quoting, order total calculation, order access, and status transitions.

## Production notes

The checked-in JWT key and credentials are for local learning only. Before public deployment use
TLS, managed secrets, asymmetric JWT signing/JWKS, separate database credentials per service,
trusted-proxy IP handling, refresh-token rotation, email verification, password reset, MFA,
backups, and explicit restaurant cache invalidation if immediate consistency is required.
