# Idempotency in a Spring Boot Order Processing System

## 1. Goal

Idempotency ensures that repeating the same client request does not create multiple orders or consume stock multiple times.

A typical request contains an idempotency key:

```http
POST /orders
Content-Type: application/json
Idempotency-Key: ABC123

{
  "userId": 1001,
  "items": [
    { "productId": 10, "quantity": 2 }
  ]
}
```

The server stores enough information to recognize later requests using the same key and, after a successful first execution, replay the original response.

---

## 2. The Core Idea

There are two separate concurrency problems:

1. **Idempotency concurrency:** two requests use the same idempotency key.
2. **Inventory concurrency:** two different orders attempt to buy the same product at the same time.

They require different protections.

| Mechanism | Purpose |
|---|---|
| `@Id` / primary key on idempotency key | Guarantees only one idempotency record can exist for a key |
| `INSERT ... ON CONFLICT DO NOTHING` | Safely handles simultaneous first-time requests for the same new key |
| `PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE` on idempotency row | Serializes processing of an existing key |
| `PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE` on product row | Prevents overselling stock |
| `requestHash` | Prevents reuse of the same key with a different payload |
| `responseJson` / response payload | Allows duplicate requests to receive the original response |
| `@Transactional` | Makes order creation, stock update, and idempotency response update atomic |

---

# 3. Idempotency Table

A simple entity can use the idempotency key itself as the primary key:

```java
@Entity
public class IdempotencyKey {

    @Id
    private String key;

    private Long userId;

    private String requestHash;

    @Column(columnDefinition = "TEXT")
    private String responseJson;
}
```

Because `key` is `@Id`:

- it is the primary key;
- it is unique;
- it cannot be null;
- PostgreSQL will reject another row with the same key.

Therefore, if `key` is already `@Id`, an additional `UNIQUE` constraint on the same column is not required.

Conceptually:

```text
idempotency_key
--------------------------------------------------
key (PRIMARY KEY)
user_id
request_hash
response_json
--------------------------------------------------
```

Example after a successful request:

```text
key      = ABC123
user_id  = 1001
hash     = 9f8e...
response = {"orderId":5001,"status":"CREATED",...}
```

---

# 4. Why a Plain `SELECT FOR UPDATE` Is Not Enough for a New Key

A repository method such as:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from IdempotencyKey i where i.key = :key")
Optional<IdempotencyKey> findByKeyWithLock(@Param("key") String key);
```

is effectively:

```sql
SELECT *
FROM idempotency_key
WHERE key = 'ABC123'
FOR UPDATE;
```

This works when `ABC123` already exists.

For a brand-new key:

```text
T1: SELECT FOR UPDATE ABC123 -> no row
T2: SELECT FOR UPDATE ABC123 -> no row
```

There is no existing row for either transaction to lock.

Therefore, the initial `SELECT FOR UPDATE` alone does not serialize creation of a nonexistent idempotency record.

---

# 5. Atomic Creation with PostgreSQL

For PostgreSQL, use an atomic insert:

```java
@Modifying(flushAutomatically = true)
@Query(value = """
    INSERT INTO idempotency_key (key, user_id, request_hash, response_json)
    VALUES (:key, :userId, :requestHash, NULL)
    ON CONFLICT (key) DO NOTHING
    """, nativeQuery = true)
int insertIfAbsent(
        @Param("key") String key,
        @Param("userId") Long userId,
        @Param("requestHash") String requestHash
);
```

Return value:

```text
1 -> this transaction inserted the row
0 -> the row already existed / another transaction won the race
```

The return value is useful for understanding/debugging, but the main flow does not need to branch purely on `1` versus `0`.

---

# 6. What `ON CONFLICT DO NOTHING` Actually Does

It does **not** return the first request's application response.

It only says:

> If the key already exists, do not insert another row.

Returning the original response is handled by the later locked read of `responseJson`.

Full mechanism:

```text
INSERT ... ON CONFLICT DO NOTHING
        |
        v
SELECT ... FOR UPDATE
        |
        +----------------------------+
        |                            |
responseJson exists?            responseJson is null
        |                            |
       YES                          NO
        |                            |
return stored response        process request
                                   |
                             store responseJson
                                   |
                                 COMMIT
```

---

# 7. First Request: Normal Successful Flow

Request:

```http
POST /orders
Idempotency-Key: ABC123

{
  "userId": 1001,
  "items": [
    { "productId": 10, "quantity": 2 }
  ]
}
```

Assume product 10 has stock `5`.

## Step 1: Start transaction

```java
@Transactional
public OrderResponse createOrder(CreateOrderRequest request) {
```

Transaction `T1` starts.

## Step 2: Compute request hash

```java
String requestHash = computeRequestHash(request);
```

Suppose:

```text
requestHash = H1
```

## Step 3: Atomically create idempotency record

```java
int inserted = idempotencyKeyRepository.insertIfAbsent(
        "ABC123",
        1001L,
        "H1"
);
```

Result:

```text
inserted = 1
```

The idempotency row now exists inside `T1`.

## Step 4: Lock the row

```java
IdempotencyKey keyRecord = idempotencyKeyRepository
        .findByKeyWithLock("ABC123")
        .orElseThrow(() -> new IllegalStateException("Idempotency key missing"));
```

Conceptually:

```sql
SELECT ...
FROM idempotency_key
WHERE key = 'ABC123'
FOR UPDATE;
```

`T1` now has the pessimistic write lock for that row.

## Step 5: Validate key ownership and request payload

```java
if (!keyRecord.getUserId().equals(request.userId())) {
    throw new IllegalArgumentException("Idempotency key belongs to a different user");
}

if (!keyRecord.getRequestHash().equals(requestHash)) {
    throw new IllegalArgumentException("Request payload does not match original idempotency key request");
}
```

`responseJson` is still `null`, so this request is not a completed duplicate.

## Step 6: Lock the product

```java
Product product = productRepository.findByIdWithLock(itemRequest.productId())
        .orElseThrow(...);
```

Conceptually:

```sql
SELECT *
FROM product
WHERE id = 10
FOR UPDATE;
```

Suppose stock is:

```text
5
```

Check:

```text
5 >= requested quantity 2
```

Update:

```text
5 -> 3
```

## Step 7: Create order and order item

```text
Order 5001
OrderItem(product=10, quantity=2)
```

## Step 8: Store response

```java
OrderResponse response = OrderResponse.from(saved);
keyRecord.setResponseJson(response.toJson());
```

Now:

```text
ABC123 -> responseJson = {"orderId":5001,...}
```

Because `keyRecord` is already a managed entity in the same transaction, explicit `save(keyRecord)` is normally unnecessary; JPA dirty checking will detect the change and flush it at commit.

## Step 9: Commit

The following become committed together:

```text
idempotency row
order row
order item row
product stock update
responseJson
```

---

# 8. Later Duplicate Request After the First Request Has Completed

The client sends the same request again:

```http
POST /orders
Idempotency-Key: ABC123

{
  "userId": 1001,
  "items": [
    { "productId": 10, "quantity": 2 }
  ]
}
```

## Flow

### Atomic insert

```java
insertIfAbsent("ABC123", 1001L, H1);
```

returns:

```text
0
```

because the row already exists.

### Lock existing row

```java
findByKeyWithLock("ABC123");
```

The row is locked.

### Validate

Same user? Yes.

Same request hash? Yes.

### Response exists?

```text
responseJson != null
```

So:

```java
return OrderResponse.fromJson(keyRecord.getResponseJson());
```

No product is locked and no second order is created.

Result:

```text
First request  -> Order 5001
Duplicate      -> Order 5001

Only one order exists.
Stock was reduced only once.
```

---

# 9. Two Simultaneous First Requests with the Same New Key

This is the important race condition.

Requests:

```text
T1 -> key = ABC123
T2 -> key = ABC123
```

Both are new.

## Timeline

```text
T1                                      T2
------------------------------------------------------------
INSERT ABC123
-> succeeds
-> returns 1

                                        INSERT ABC123
                                        -> same PK/key is uncommitted
                                        -> waits
```

The important fact is that PostgreSQL's primary-key/unique constraint sees T1's uncommitted insert as a conflicting insert.

T2 cannot immediately insert another `ABC123` row.

## If T1 commits

```text
T1                                      T2
------------------------------------------------------------
INSERT ABC123

SELECT FOR UPDATE

process order

store responseJson

COMMIT
                                        waiting INSERT continues
                                        -> ON CONFLICT
                                        -> DO NOTHING
                                        -> returns 0

                                        SELECT FOR UPDATE
                                        -> obtains row

                                        responseJson != null
                                        -> return T1 response
```

The second request gets the first request's response through the stored `responseJson`.

## If T1 rolls back

```text
T1                                      T2
------------------------------------------------------------
INSERT ABC123

process order fails

ROLLBACK
                                        waiting INSERT continues
                                        -> conflict disappears
                                        -> T2 INSERT succeeds
                                        -> returns 1

                                        SELECT FOR UPDATE
                                        -> finds its own row

                                        responseJson == null
                                        -> process the order itself
```

This is correct: a failed request should not permanently reserve the idempotency key.

---

# 10. Important Timing Question: What if T2 Inserts Before T1 Locks Its Row?

Consider:

```text
T1: INSERT ABC123 -> returns 1
T1: has not yet executed SELECT FOR UPDATE
T2: INSERT ABC123
```

T2 still does **not** get `1`.

T1's insert is already an uncommitted write protected by the primary-key uniqueness rule.

T2 sees the conflicting uncommitted key and waits for T1's transaction outcome.

The sequence is therefore:

```text
T1: INSERT ABC123 -> 1

T2: INSERT ABC123 -> WAIT

T1: either COMMIT or ROLLBACK

If COMMIT:
    T2 -> conflict -> DO NOTHING -> 0

If ROLLBACK:
    T2 -> insert succeeds -> 1
```

This means the correctness of concurrent first-request creation does **not** depend on T1 executing `SELECT ... FOR UPDATE` quickly enough.

The primary-key/unique conflict is the synchronization point for the insert race.

---

# 11. What the Two Locks Mean

There are two logically different locks.

## Lock A: Idempotency row lock

```sql
SELECT *
FROM idempotency_key
WHERE key = ?
FOR UPDATE;
```

Purpose:

```text
Serialize requests using the same idempotency key.
```

Example:

```text
T1: lock ABC123
T2: try lock ABC123 -> WAIT
T1: commit
T2: gets lock
```

## Lock B: Product row lock

```sql
SELECT *
FROM product
WHERE id = ?
FOR UPDATE;
```

Purpose:

```text
Serialize concurrent stock updates for the same product.
```

Example:

```text
Stock = 5

T1 orders 3
T2 orders 3

T1: lock product
T2: wait
T1: stock 5 -> 2
T1: commit
T2: reads stock 2
T2: 2 < 3 -> insufficient stock
```

These locks solve different problems.

---

# 12. Why Product Locking Is Needed Even with Idempotency

Idempotency does not prevent two different orders from competing for stock.

Example:

```text
User A -> key AAA -> product 10 x 3
User B -> key BBB -> product 10 x 3
```

Keys are different:

```text
AAA != BBB
```

Therefore the idempotency lock does not serialize them.

The product lock is required:

```text
idempotency lock -> protects request identity
product lock     -> protects inventory state
```

---

# 13. Request Hash

The same idempotency key should represent the same logical request.

Example first request:

```http
Idempotency-Key: ABC123

{
  "userId": 1001,
  "items": [
    { "productId": 10, "quantity": 2 }
  ]
}
```

Later someone tries:

```http
Idempotency-Key: ABC123

{
  "userId": 1001,
  "items": [
    { "productId": 10, "quantity": 5 }
  ]
}
```

The key is the same but the payload is different.

The stored hash detects this:

```java
if (!keyRecord.getRequestHash().equals(requestHash)) {
    throw new IllegalArgumentException(
            "Request payload does not match original idempotency key request");
}
```

This prevents accidental or malicious reuse of an idempotency key for a different operation.

---

# 14. Same Key but Different User

Example:

```text
First request:
key = ABC123
user = 1001

Second request:
key = ABC123
user = 2002
```

The second request should be rejected:

```java
if (!keyRecord.getUserId().equals(request.userId())) {
    throw new IllegalArgumentException(
            "Idempotency key belongs to a different user");
}
```

The idempotency key is therefore bound to its original user.

---

# 15. What Happens When the First Request Fails

Suppose:

```text
T1 -> insert key
T1 -> lock product
T1 -> stock check fails
```

Because the entire method is transactional:

```java
@Transactional
```

T1 rolls back.

The rollback removes:

```text
idempotency row
order row
order item row
stock change
```

There is no successful response to replay.

A later request using the same key can become the successful first transaction.

This is why an idempotency placeholder should not be treated as permanently successful merely because the key row exists.

---

# 16. What `flushAutomatically = true` Does

Repository:

```java
@Modifying(flushAutomatically = true)
```

means Hibernate flushes pending changes in the persistence context before executing the modifying query.

It does **not** mean:

```text
flush = commit
```

The transaction remains open.

`flushAutomatically` can solve situations where pending JPA changes need to be synchronized to the database before a modifying/native query executes.

It is reasonable to use here, especially if tests showed ordering/flush-related behavior, but it is **not the fundamental source of concurrency safety**.

Concurrency safety comes from:

```text
primary key / unique constraint
+
INSERT ... ON CONFLICT DO NOTHING
+
SELECT ... FOR UPDATE
```

---

# 17. Why `save(keyRecord)` Is Usually Unnecessary at the End

If you load the entity like this inside the same transaction:

```java
keyRecord = idempotencyKeyRepository
        .findByKeyWithLock(key)
        .orElseThrow(...);
```

then `keyRecord` is normally a managed JPA entity.

Therefore this is enough:

```java
keyRecord.setResponseJson(response.toJson());
```

At flush/commit Hibernate detects the changed field and issues the `UPDATE`.

This is usually redundant:

```java
keyRecord.setResponseJson(response.toJson());
idempotencyKeyRepository.save(keyRecord);
```

It is not harmful in many setups, but it is unnecessary when the entity is already managed.

---

# 19. Recommended Repository Methods

## Idempotency insert

PostgreSQL:

```java
@Modifying(flushAutomatically = true)
@Query(value = """
    INSERT INTO idempotency_key (key, user_id, request_hash, response_json)
    VALUES (:key, :userId, :requestHash, NULL)
    ON CONFLICT (key) DO NOTHING
    """, nativeQuery = true)
int insertIfAbsent(
        @Param("key") String key,
        @Param("userId") Long userId,
        @Param("requestHash") String requestHash
);
```

## Idempotency lock

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from IdempotencyKey i where i.key = :key")
Optional<IdempotencyKey> findByKeyWithLock(
        @Param("key") String key
);
```

## Product lock

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Product p where p.id = :id")
Optional<Product> findByIdWithLock(@Param("id") Long id);
```

---

# 20. Complete Concurrency Model

## Case A: First request only

```text
Request
  |
  v
INSERT key -> 1
  |
  v
SELECT key FOR UPDATE
  |
  v
Lock product(s)
  |
  v
Check stock
  |
  v
Create order
  |
  v
Store responseJson
  |
  v
COMMIT
```

## Case B: Duplicate after completion

```text
Request
  |
  v
INSERT key -> 0
  |
  v
SELECT key FOR UPDATE
  |
  v
responseJson exists
  |
  v
Return stored response
```

## Case C: Two simultaneous first requests, first succeeds

```text
T1: INSERT -> 1
T2: INSERT -> WAIT

T1: process
T1: responseJson
T1: COMMIT

T2: conflict -> DO NOTHING -> 0
T2: SELECT FOR UPDATE
T2: responseJson exists
T2: return T1 response
```

## Case D: Two simultaneous first requests, first fails

```text
T1: INSERT -> 1
T2: INSERT -> WAIT

T1: processing fails
T1: ROLLBACK

T2: INSERT succeeds -> 1
T2: SELECT FOR UPDATE
T2: process order
T2: store responseJson
T2: COMMIT
```

## Case E: Two different keys, same product

```text
T1: key AAA -> lock product 10
T2: key BBB -> waits on product 10

T1: stock 5 -> 2 -> COMMIT
T2: reads 2
T2: requested 3
T2: insufficient stock
```

## Case F: Same key, different payload

```text
ABC123 + hash H1 -> original
ABC123 + hash H2 -> reject
```

## Case G: Same key, different user

```text
ABC123 + user 1001 -> original
ABC123 + user 2002 -> reject
```

---

# 21. Why `responseJson` Can Be Stored as `TEXT`

If the application treats the serialized response as an opaque payload:

```java
keyRecord.setResponseJson(response.toJson());
```

and later:

```java
OrderResponse.fromJson(keyRecord.getResponseJson());
```

then PostgreSQL `TEXT` is sufficient.

You do not need `JSONB` unless the database needs to query/manipulate fields inside the JSON or you specifically want database-side JSON validation/query support.

Example:

```java
@Column(columnDefinition = "TEXT")
private String responseJson;
```

Conceptually:

```text
Database:
"Here is the response we stored."

Application:
"Deserialize it and replay it."
```

---

# 22. Common Misconceptions

## `@Id` automatically returns the first response

False.

`@Id` only guarantees uniqueness of the key. Your service must read `responseJson` and return it.

## `PESSIMISTIC_WRITE` alone handles a brand-new key

Not necessarily.

If the row does not exist, the initial `SELECT ... FOR UPDATE` has no row to lock. Atomic insertion plus the primary-key conflict is required to handle the creation race.

## `ON CONFLICT DO NOTHING` returns the first response

False.

It only prevents a duplicate insert. The application reads the existing row afterward.

## `insertIfAbsent() == 0` always means a completed request exists

False.

The other transaction may still be processing. The locked read of the row and checking `responseJson` determines whether the operation is complete.

## `insertIfAbsent() == 1` means this HTTP request was absolutely the first request ever sent

Not strictly.

It means this transaction successfully created the idempotency row. A previous transaction could have attempted the request but rolled back.

## `flushAutomatically = true` is the concurrency mechanism

False.

It controls synchronization of the JPA persistence context before the modifying query. The database constraints and locks provide the concurrency semantics.

---

# 23. Deadlock Consideration for Multiple Products

A transaction can need multiple product locks.

Potential problem:

```text
T1 locks product 10
T1 then tries product 20

T2 locks product 20
T2 then tries product 10
```

This can create a deadlock:

```text
T1 waits for T2
T2 waits for T1
```

A common mitigation is to lock multiple products in a consistent order, for example ascending `productId`:

```text
10 -> 20 -> 30
```

Then all transactions acquire product locks in the same order.

---

# 24. Practical Tests to Write

## Test 1: Normal first request

```text
POST /orders
Idempotency-Key: TEST-1
```

Expected:

```text
HTTP 200
1 order created
1 idempotency row
stock decreased once
responseJson stored
```

## Test 2: Sequential duplicate

Send the exact same request twice.

Expected:

```text
same order ID
same response
one order in DB
stock decreased only once
```

## Test 3: Same key, different payload

First:

```json
{
  "items": [{"productId": 10, "quantity": 2}]
}
```

Second:

```json
{
  "items": [{"productId": 10, "quantity": 5}]
}
```

Expected:

```text
request rejected
no second order
```

## Test 4: Same key, different user

Expected:

```text
request rejected
```

## Test 5: Concurrent first requests

Send two requests simultaneously with the same new key.

Expected:

```text
one order
one stock reduction
both successful requests return the same order response
one idempotency row
```

## Test 6: Concurrent different keys, same product

Example:

```text
AAA -> product 10 x 3
BBB -> product 10 x 3
stock = 5
```

Expected:

```text
one order succeeds
one request gets insufficient stock
final stock = 2
```

## Test 7: First concurrent request rolls back

Force T1 to fail after creating the idempotency row.

Expected:

```text
T1 rollback
T2 eventually inserts successfully
T2 creates the order
T2 stores response
```

---

# 25. Final Mental Model

Remember these three layers:

```text
1. PRIMARY KEY / UNIQUE
   "Only one idempotency record for this key can exist."

2. INSERT ... ON CONFLICT DO NOTHING
   "Make concurrent first-time creation safe."

3. SELECT ... FOR UPDATE
   "Once the row exists, serialize requests using this key."
```

Then, separately:

```text
PRODUCT FOR UPDATE
   "Serialize stock consumption."
```

And finally:

```text
responseJson
   "Replay the successful first response."
```

The overall design is:

```text
                         HTTP Request
                              |
                              v
                INSERT idempotency key atomically
                              |
                              v
                  SELECT key FOR UPDATE
                              |
                    +---------+---------+
                    |                   |
             response exists?          |
                    |                   |
                   YES                  NO
                    |                   |
                    v                   v
              return stored       lock product(s)
                 response               |
                                       v
                                  check stock
                                       |
                                       v
                                  create order
                                       |
                                       v
                                store responseJson
                                       |
                                       v
                                    COMMIT
```

That is the complete idempotency + inventory concurrency model for the order-processing system.
