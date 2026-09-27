# Order platform — Stage 3 (CRUD + transactions + pessimistic locking + idempotency)

Minimal order system: `Product`, `Order`, `OrderItem`, backed by PostgreSQL,
with schema managed by Flyway. Includes pessimistic locking (Stage 2) and
idempotency key handling (Stage 3) to prevent duplicate orders and race
conditions.

## New Features

### Pessimistic Locking (Stage 2)
- **Problem Solved**: Race conditions during concurrent stock updates
- **Solution**: JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` for database-level exclusive access
- **Test Coverage**: Comprehensive concurrent request testing (`OrderControllerIntegrationTest`)
- **Result**: Prevents stock overselling in high-concurrency scenarios

### Idempotency Key (Stage 3)
- **Problem Solved**: Duplicate `POST /orders` from retries/network timeouts creates multiple orders
- **Solution**: Client-provided `idempotencyKey` with SHA-256 payload hash + pessimistic DB lock + atomic insert (`ON CONFLICT DO NOTHING`)
- **Components**: `IdempotencyKey` entity, `IdempotencyKeyRepository` (native `insertIfAbsent` + `findByKeyWithLock`), `OrderService` validation/storage
- **Concurrency**: `insertIfAbsent` (blocking) + `findByKeyWithLock`  serializes duplicates; rollback handled via `insertIfAbsent`
- **Test Coverage**: `IdempotencyIntegrationTest` (8 scenarios: first-time, duplicate, concurrent first-time, concurrent duplicate, different payload, different user, no-key normal, no-key concurrent)

## Implementation Details

### Idempotency Flow
1. Compute `requestHash` = SHA-256(`userId|items.toString()`)
2. Atomically insert `idempotency_key` (`ON CONFLICT DO NOTHING`)
3. Lock row (`PESSIMISTIC_WRITE`)
4. Validate: same user + same hash; if `responseJson` exists → return cached `OrderResponse`
5. Process order (with product locks)
6. Save `responseJson` to managed `IdempotencyKey` entity (`.save()`)

### Key Design Decisions
- `flushAutomatically = true` on native `insertIfAbsent`: ensures `findByKeyWithLock` sees the inserted row in same transaction
- `TEXT` column for `response_json`: PostgreSQL `JSON` type rejects `String` expressions; `TEXT` avoids casting errors
- `JavaTimeModule` registered in `ObjectMapper`: `Instant` serialization (`createdAt`) requires it
- `IllegalArgumentException` handled in `GlobalExceptionHandler`: returns 400 (not 500) for idempotency validation errors

## Run it

1. Start Postgres:
   ```
   docker compose up -d
   ```

2. Run the app:

   (Flyway runs `V1__init.sql` and `V2__idempotency_key.sql` automatically on startup.)

3. Try it:
   ```
   curl -X POST localhost:8080/products \
     -H "Content-Type: application/json" \
     -d '{"sku":"SKU-1","name":"Widget","price":9.99,"availableQuantity":10}'

   # Without idempotency key
   curl -X POST localhost:8080/orders \
     -H "Content-Type: application/json" \
     -d '{"userId":101,"items":[{"productId":1,"quantity":2}]}'

   # With idempotency key (duplicate-safe)
   curl -X POST localhost:8080/orders \
     -H "Content-Type: application/json" \
     -d '{"userId":101,"items":[{"productId":1,"quantity":2}],"idempotencyKey":"key-1"}'

   curl localhost:8080/orders/1
   ```

## Run the tests

```
mvn test
```

Tests use Testcontainers with a real PostgreSQL container (`postgres:16`). Docker must be running.
- `OrderControllerIntegrationTest`: CRUD + concurrent stock locking
- `IdempotencyIntegrationTest`: idempotency (first-time, duplicate, concurrent, different payload/user, rollback)

## What's missing as of now
- **No payment, no Kafka, no Redis** — arrives in later stages
- **No optimistic locking alternative** — could explore OCC for read-heavy workloads
- **No distributed locking** — for microservices scenarios
- **No advanced inventory management** — backordering, reservations, etc.