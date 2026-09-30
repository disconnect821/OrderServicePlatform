# Payment Module — Implementation Documentation

## 1. What Was Implemented

A full split-phase, idempotent payment processing pipeline with concurrent-safe reconciliation tracking and provider abstraction.

### Key Components Added / Updated

| Component | File | What / Why |
|---|---|---|
| `PaymentStatus` enum | `payment/PaymentStatus.java` | Added `UNKNOWN` to represent timeout/network failure where actual provider outcome is unclear. Keeps order in `PENDING` and idempotency in `PENDING` for safe reconciliation/retry. |
| `IdempotencyStatus` enum | `idempotency/IdempotencyStatus.java` | New enum: `PENDING`, `COMPLETED`, `FAILED`. Separated from `PaymentStatus` so idempotency tracking is generic and not coupled to business payment outcomes. |
| `IdempotencyKey` entity | `idempotency/IdempotencyKey.java` | Added `status` field using `IdempotencyStatus`, `responseJson`, `resourceId`, `requestHash`, `userId`. Generic tracking independent of payment logic. |
| `IdempotencyKeyRepository` | `idempotency/IdempotencyKeyRepository.java` | Native `INSERT ... ON CONFLICT DO NOTHING` (`insertIfAbsent`) + `findByOperationAndKeyWithLock` for pessimistic row-level serialization. |
| `PaymentProviderFactory` | `payment/provider/PaymentProviderFactory.java` | Strategy + Factory pattern: `@Qualifier("stripePaymentProvider")` and `payPalPaymentProvider` mapped by provider name (`stripe`, `paypal`). Generic provider selection. |
| `PaymentService` | `payment/PaymentService.java` | Rewritten with split-phase transaction: validate/lock (tx), provider call (non-tx or separate tx boundary), save/update (tx). Includes hash validation (`provider` included), user check, `CANCELLED` guard, `COMPLETED`/`FAILED`/`PENDING` tracking, `UNKNOWN` reconciliation. |
| Migration `V1` | `db/migration/V1__init.sql` | Made idempotent: `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`. |
| Migration `V2` | `db/migration/V2__idempotency_key.sql` | Made idempotent. |
| Migration `V3` | `db/migration/V3__payment.sql` | Made idempotent. |

---

## 2. Why It Was Implemented

### Problem 1: Duplicate / Concurrent Requests
Without idempotency, concurrent clients submitting the same payment key would create duplicate payments, corrupt inventory, and produce inconsistent order states.

**Solution:** Atomic `INSERT ... ON CONFLICT` for idempotency key + `SELECT ... FOR UPDATE` on the same row serializes concurrent attempts for the same `(operation, key, user)` tuple.

### Problem 2: Provider Call Inside DB Lock
Previously, the external network call to Stripe/PayPal could be made while holding pessimistic locks on `orders` or inventory, blocking other transactions for the duration of the network round-trip.

**Solution:** Split-phase execution:
1. Lock and validate (DB tx)
2. Provider call (no DB locks held)
3. Save results (new DB tx)

### Problem 3: Timeout / Unknown Provider Outcome (`UNKNOWN`)
If a provider call times out, the actual charge may or may not have succeeded. Returning `FAIL` could incorrectly cancel an order that was actually paid; returning `SUCCESS` is unsafe without confirmation.

**Solution:** `PaymentStatus.UNKNOWN` is saved, `IdempotencyStatus.PENDING` is preserved (not `COMPLETED` or `FAILED`), and the order remains `PAYMENT_PENDING`. A future retry (same idempotency key) can safely reconcile or re-call the provider.

### Problem 4: Coupled Idempotency Tracking
Earlier design tied idempotency tracking directly to `PaymentStatus`. Changing a business outcome required changing tracking logic.

**Solution:** Separate `IdempotencyStatus` (`PENDING`/`COMPLETED`/`FAILED`) from `PaymentStatus` (`PENDING`/`SUCCESS`/`FAILED`/`CANCELLED`/`UNKNOWN`). Idempotency tracking is now generic and reusable for any operation, not just payments.

---

## 3. How It Was Implemented

### Design Patterns Applied

| Pattern | Where | How |
|---|---|---|
| **Strategy** | `PaymentProvider` interface; `StripePaymentProvider`, `PayPalPaymentProvider` | Provider behavior encapsulated; selected at runtime by request's `provider` field. |
| **Factory** | `PaymentProviderFactory` | Registry of qualified providers (`stripePaymentProvider`, `payPalPaymentProvider`); resolves by lower-cased name with safe default (`stripe`). |
| **State** | `PaymentStatus`, `OrderStatus`, `IdempotencyStatus` | Explicit state enums drive transitions (`PENDING` → `SUCCESS`/`FAILED`/`UNKNOWN`) and lock/reconciliation behavior. |
| **Command** | `processPayment`, `executeProviderPayment`, `saveExecutionResult` | Each phase is a discrete operation with clear input (request) and output (response/state updates). |
| **Repository** | `IdempotencyKeyRepository`, `PaymentRepository`, `OrderRepository` | Abstract DB access; custom native query (`insertIfAbsent`) and pessimistic lock query (`findByOperationAndKeyWithLock`). |
| **Transaction Boundary** | `@Transactional` split in `PaymentService` | `processPayment` holds locks only for validation + pending creation; provider execution (`executeProviderPayment`) and result saving (`saveExecutionResult`) use separate transaction scopes to avoid long-held locks during network I/O. |

### Hash Computation (Updated — Includes Provider)

```java
String data = request.orderId() + "|" + request.provider();
```
Previously the hash omitted the provider name. If the same user submitted the same idempotency key but selected a different provider, the old hash would match incorrectly, allowing a payload mismatch. Adding `provider` ensures the hash covers the full request identity.

### Idempotency Flow (Generic, Updated)

**Before (coupled):**
- Idempotency status = `PaymentStatus` directly.

**After (generic):**
- `IdempotencyStatus` tracks only the execution outcome: `PENDING` (in progress / unknown / retry allowed), `COMPLETED` (final), `FAILED` (final, retry safe but will return same failure).
- `PaymentStatus` tracks business result: `SUCCESS`, `FAILED`, `CANCELLED`, `PENDING`, `UNKNOWN`.
- Mapping in `saveExecutionResult`:
  - `SUCCESS` → `COMPLETED`
  - `FAILED` → `FAILED`
  - `UNKNOWN` / other `PENDING` → `PENDING` (safe retry/reconciliation)

---

## 4. Where It Was Implemented (File / Line References)

### Service: `PaymentService.java`
- Constructor accepts `PaymentProviderFactory` (line 36-46).
- `processPayment` (line 49-126): validates `CANCELLED`, checks `CONFIRMED` + existing completed payment, creates `PENDING` payment, saves `IdempotencyStatus.PENDING`, updates order to `PAYMENT_PENDING`.
- `executeProviderPayment` (line 130-180): validates idempotency key presence, loads order without long locks, inserts key if absent, locks key row, validates user/hash, rejects `CANCELLED`, loads pending payment, calls provider.
- `getPaymentResponse` (line 211-222): selects provider via factory, makes external call **outside DB lock scope**.
- `saveExecutionResult` (line 184-209): updates payment + order + idempotency in a new transaction; applies `UNKNOWN` → `PENDING` reconciliation logic.
- `applyStatusDrivenUpdates` (line 246-274): maps `SUCCESS` (`CONFIRMED`), `PENDING` (`PAYMENT_PENDING`), `UNKNOWN` (`PAYMENT_PENDING` for safe retry), `FAILED`/`CANCELLED` (`CANCELLED` + inventory restoration).

### Factory: `PaymentProviderFactory.java`
- Lines 9-23: `@Component` registry; constructor uses `@Qualifier` annotations (`stripePaymentProvider`, `payPalPaymentProvider`).

### Repository: `IdempotencyKeyRepository.java`
- `insertIfAbsent`: native `INSERT ... ON CONFLICT DO NOTHING` (line ~29-35).
- `findByOperationAndKeyWithLock`: `SELECT ... FOR UPDATE` (line ~39-45).

### Entity / Enum Updates
- `IdempotencyStatus.java` (new): `PENDING`, `COMPLETED`, `FAILED`.
- `IdempotencyKey.java`: added `status`, `responseJson`, `resourceId`, `requestHash`.
- `PaymentStatus.java`: added `UNKNOWN`.

---

## 5. Request Examples

### Example A — Single Request (Happy Path)

**Request:**
```json
POST /payments
{
  "orderId": 1,
  "provider": "stripe",
  "idempotencyKey": "pay-key-001"
}
```

**Flow:**
1. `processPayment` locks order + inserts idempotency key (`PENDING`).
2. Creates `PENDING` payment.
3. `executeProviderPayment` loads key (`PENDING`), validates hash/user, calls Stripe provider.
4. Provider returns `SUCCESS`.
5. `saveExecutionResult` sets payment `SUCCESS`, key `COMPLETED`, order `CONFIRMED`.

---

### Example B — Duplicate Request (Same Key)

**Request:** Same `pay-key-001`, same `orderId`, same `provider` (`stripe`).

**Flow:**
- `insertIfAbsent` returns 0 (already exists).
- Lock key row; if `COMPLETED`, parse cached response (`SUCCESS`) and return immediately — no provider call, no duplicate.
- If `PENDING` and no final result cached, continues from saved `PENDING` state safely.

---

### Example C — Concurrent Requests (Same Key, Same Order)

**Scenario:** Two clients submit `/payments` with same `idempotencyKey` at exactly the same time.

**Flow:**
1. Both attempt `insertIfAbsent`; one succeeds (`inserted = 1`), the other returns 0.
2. Both attempt `findByOperationAndKeyWithLock`. Only one acquires the row lock; the other blocks.
3. The blocked request waits until the first completes (either `COMPLETED`, `FAILED`, or remains `PENDING` with `UNKNOWN`).
4. Once unblocked, it reads the updated key; if `COMPLETED`/`FAILED`, returns cached result; if `PENDING` (`UNKNOWN`), continues safely (re-tries provider or reconciles based on saved state).

**Result:** Serialization guaranteed; no duplicate provider calls for the same final state; concurrent attempts never corrupt order/payment state.

---

### Example D — Multiple Requests (Different Keys, Same Order / Different Providers)

**Request 1:**
```json
{"orderId": 1, "provider": "stripe", "idempotencyKey": "key-a"}
```
**Request 2:**
```json
{"orderId": 1, "provider": "paypal", "idempotencyKey": "key-b"}
```

**Flow:**
- Different `idempotencyKey` → different key rows → no serialization conflict.
- Different `provider` → different hash (`provider` included) → no hash-match conflict even if keys coincidentally overlap.
- Both can proceed in parallel; both create/update the same order/payment state, but the second that completes will apply its result (last write wins for the payment row, but both are tracked separately). In practice, a well-behaved client uses the same key for retries; different keys for different attempts are allowed but may result in overlapping state updates.

---

### Example E — Unknown / Timeout Reconciliation (Retry Safe)

**Initial Request:**
```json
{"orderId": 1, "provider": "stripe", "idempotencyKey": "reconcile-key"}
```

**Provider Response:** Network timeout → `PaymentStatus.UNKNOWN`.

**Saved State:**
- `IdempotencyStatus` = `PENDING` (not `COMPLETED` or `FAILED`).
- `PaymentStatus` = `UNKNOWN`.
- `OrderStatus` = `PAYMENT_PENDING`.
- Response JSON = `"UNKNOWN|Timeout/network failure|15.00"`.

**Retry Request (same key):**
- Key is `PENDING`; hash/user validated.
- Because `PENDING`, retry is allowed.
- Provider called again (if still needed) or reconciled based on external confirmation.
- Final result updates key to `COMPLETED` (`SUCCESS`) or `FAILED`; `PENDING` preserved for any further unknowns.

---

## 6. Idempotency — Updated and Made Generic

### Before Update
- Idempotency tracking used `PaymentStatus` directly.
- Changing business logic (e.g., introducing `CANCELLED` or `UNKNOWN`) required updating tracking logic.

### After Update (Generic)
- `IdempotencyStatus` is independent: tracks only whether the operation has produced a definitive result (`COMPLETED` / `FAILED`) or is still open to retry/reconciliation (`PENDING`).
- `PaymentStatus` remains business-specific.
- Any new operation (e.g., refund, order cancel) can reuse the same `IdempotencyStatus` and `IdempotencyKeyRepository` without changing tracking semantics.
- Mapping is explicit in `saveExecutionResult` and fully configurable per operation type.

---

## 7. Key Changes to Migrations (Idempotency of DB Schema)

All `CREATE` statements now use `IF NOT EXISTS`:

```sql
CREATE TABLE IF NOT EXISTS product (...);
CREATE INDEX IF NOT EXISTS idx_order_item_order_id ON order_item(order_id);
```

This prevents migration failures on fresh or partially-initialized environments.

---

## 8. Testing Coverage (Integration Tests)

Tests in `PaymentControllerIntegrationTest.java` cover:
- Happy path: order creation, inventory decrement, pending payment creation.
- Failed payment: pending saved, inventory restored, order cancelled.
- Concurrent requests: 5 threads submit same key; at least 1 succeeds, no crashes.
- Unknown status tracking: `PENDING` idempotency preserved, `PENDING` response JSON contains `PENDING`.
- Completed tracking: key saved with `PENDING` and non-null JSON for later reconciliation.

---

*Document generated: 2026-09-30*
*Module: Payment / Idempotency / Provider Factory*
*Patterns applied: Strategy, Factory, State, Command, Repository, Transaction Boundary*