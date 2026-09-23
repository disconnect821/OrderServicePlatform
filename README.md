# Order platform - Stage 1 (CRUD + transactions)

Minimal order system: `Product`, `Order`, `OrderItem`, backed by PostgreSQL,
with schema managed by Flyway. No Kafka, no Redis, no locking yet - this is
intentionally the simplest version.

## Run it

1. Start Postgres:
   ```
   docker compose up -d
   ```

2. Run the app:
   
   (Flyway runs `V1__init.sql` automatically on startup.)

3. Try it:
   ```
   curl -X POST localhost:8080/products \
     -H "Content-Type: application/json" \
     -d '{"sku":"SKU-1","name":"Widget","price":9.99,"availableQuantity":10}'

   curl -X POST localhost:8080/orders \
     -H "Content-Type: application/json" \
     -d '{"userId":101,"items":[{"productId":1,"quantity":2}]}'

   curl localhost:8080/orders/1
   ```

## Run the tests

```
./mvnw test
```

The integration test uses Test containers, so Docker must be running - it
spins up a real throwaway Postgres for the test, rather than mocking the
database.

## What's  missing (see Stage 2+)
- **No idempotency key** - sending the same `POST /orders` twice creates two
  orders.
- **No locking** - `OrderService.createOrder` reads `available_quantity` then
  writes it back with no lock, so two concurrent requests for the last unit
  of stock can both succeed and oversell. The comment in `OrderService` marks
  exactly where this breaks.
- **No payment, no Kafka, no Redis** - those arrive in later stages.

