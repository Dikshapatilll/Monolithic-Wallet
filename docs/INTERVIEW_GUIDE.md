# Monolith Wallet System: Comprehensive Interview Guide

**Prepared for:** SDE Intern Interview at IDeaS Revenue Solutions, Pune
**Project:** Hardened Monolith Wallet System (Java, Spring Boot, Spring Security, JPA/Hibernate, MySQL)

---

## 1. 30-Second Elevator Pitch

> "I built a secure, monolithic digital wallet backend using Spring Boot, Spring Security, MySQL, and JPA. It supports user registration, JWT-based stateless authentication, multi-account creation, deposits, withdrawals, and peer-to-peer transfers. To make it robust and interview-ready, I implemented pessimistic row-level locking with ascending-order deadlock prevention, idempotency key guarantees against duplicate debits, BOLA/IDOR ownership checks on all financial endpoints, and a cumulative daily transfer limit enforced via index-backed database aggregations."

---

## 2. 2-Minute In-Depth Project Walkthrough

> "My goal with this project was to take a typical college tutorial-style wallet app and re-engineer it according to professional backend engineering standards.
>
> "Architecturally, it’s a 3-tier monolith: Controller, Service, and Repository layers. On the security side, user passwords are protected with BCrypt work factor 10, and authentication is handled using stateless HMAC-SHA256 JWT tokens. I hardened the security boundary by ensuring that financial endpoints—deposits, withdrawals, transfers, and transaction history—strictly enforce Broken Object Level Authorization (BOLA). A user can never read or mutate an account that doesn't belong to them.
>
> "For transactional correctness, all balance-mutating operations run within Spring `@Transactional` boundaries with `READ_COMMITTED` isolation. The most critical challenge was concurrency: if two transfers withdraw from the same account at the exact same moment, standard reads lead to lost updates and negative balances. I solved this by applying JPA `PESSIMISTIC_WRITE` locking (`SELECT ... FOR UPDATE` in MySQL InnoDB). To eliminate deadlocks when Alice and Bob transfer to each other simultaneously, I implemented lock ordering where accounts are always locked in ascending numerical ID order (`min(fromId, toId)` first, then `max(fromId, toId)`).
>
> "I also solved a subtle data-modeling flaw where transfers previously inserted duplicate records that polluted history views; now, transfers write a single unified ledger record dynamically projected into `TRANSFER_OUT` or `TRANSFER_IN` depending on the requester. Finally, I added idempotency key replay and cumulative daily transfer limits, and verified the entire system with 29 automated JUnit and MockMvc integration tests covering edge cases and concurrent execution."

---

## 3. Architecture & Request Flow

```
[ Client Request ]
       │  (e.g., POST /api/accounts/transfer + Bearer Token)
       ▼
[ Security Filter Chain: JwtFilter ]
  - Extracts Bearer token from Authorization header.
  - Validates signature and expiration via JwtUtil.
  - Builds UsernamePasswordAuthenticationToken and stores it in SecurityContextHolder.
       │
       ▼
[ Controller Layer: AccountController / AuthController ]
  - Validates request payloads using Jakarta Bean Validation (@Valid, @NotNull, @DecimalMin).
  - Handles HTTP status codes (200 OK, 201 Created, 400 Bad Request).
       │
       ▼
[ Service Layer: AccountService / AuthService ]
  - Extracts authenticated principal from SecurityContextHolder.
  - Enforces account ownership checks (rejects unauthorized access with 403 Forbidden).
  - Checks business invariants (positive amounts, non-self transfers, balance sufficiency).
  - Checks daily transfer limit aggregation.
  - Acquires pessimistic row locks in deterministic ascending order.
  - Coordinates balance updates and single ledger record insertion inside a single @Transactional boundary.
       │
       ▼
[ Repository Layer: AccountRepository, UserRepository, TransactionHistoryRepository ]
  - Spring Data JPA repositories backed by Hibernate.
  - Executes indexed queries and SELECT ... FOR UPDATE queries.
       │
       ▼
[ Database: MySQL InnoDB ]
  - Enforces ACID guarantees, row-level locks, foreign key relationships, and unique constraints.
```

---

## 4. Database Schema & Entity Relationships

The schema consists of three core relational tables:

```
┌─────────────────────────┐         1 : N         ┌─────────────────────────┐
│        app_user         │ ────────────────────< │        accounts         │
├─────────────────────────┤                       ├─────────────────────────┤
│ id (PK, BigInt)         │                       │ id (PK, BigInt)         │
│ username (VARCHAR(50),U)│                       │ username (VARCHAR(255)) │
│ password (VARCHAR(255)) │                       │ balance (DECIMAL(19,2)) │
└─────────────────────────┘                       │ user_id (FK -> app_user)│
                                                  └─────────────────────────┘
                                                               │
                                                               │ 1 : N
                                                               ▼
                                                  ┌─────────────────────────┐
                                                  │   transaction_history   │
                                                  ├─────────────────────────┤
                                                  │ id (PK, BigInt)         │
                                                  │ sender_id (BigInt, IDX) │
                                                  │ receiver_id (BigInt,IDX)│
                                                  │ amount (DECIMAL(19,2))  │
                                                  │ type (VARCHAR(20))      │
                                                  │ status (VARCHAR(20))    │
                                                  │ idempotency_key (U, IDX)│
                                                  │ created_at (TIMESTAMP)  │
                                                  │ updated_at (TIMESTAMP)  │
                                                  └─────────────────────────┘
```

### Key Schema Decisions:
1. **Monetary Precision (`DECIMAL(19, 2)`):** Floating-point numbers (`FLOAT` or `DOUBLE`) introduce IEEE 754 precision issues (e.g. `0.1 + 0.2 = 0.30000000000000004`). We strictly map `BigDecimal` to `DECIMAL(19, 2)` in MySQL to ensure financial precision.
2. **Database Indexes:**
   - `idx_tx_sender (sender_id, created_at)`: Optimizes daily limit aggregation and sender history pagination.
   - `idx_tx_receiver (receiver_id, created_at)`: Optimizes recipient history pagination.
   - `idx_tx_idempotency (idempotency_key)`: Unique constraint enforcing idempotency lookups in $O(1)$.
3. **AppUser to Account Relationship:** One-to-many relationship (`@OneToMany` with `fetch = FetchType.LAZY`), allowing a single user to own multiple wallet accounts.

---

## 5. Security Deep Dive: JWT & BCrypt

### 5.1 BCrypt Password Hashing
- **Algorithm:** Adaptive hash function based on the Blowfish cipher.
- **Salt Generation:** Incorporates a cryptographic 16-byte random salt generated automatically per password, protecting against Rainbow Table lookups.
- **Work Factor (Cost):** Set to 10 ($2^{10} = 1024$ key expansion rounds), balancing verification speed with brute-force resistance.
- **Defense in Depth:** In `AppUser`, password has `@JsonIgnore` and is never returned in API responses.

### 5.2 JWT Stateless Authentication
- **Structure:** `Header.Payload.Signature`
- **Signing Key:** HMAC-SHA256 using a minimum 256-bit secret injected from environment variables (`jwt.secret`).
- **Validation:** `JwtFilter` validates signature and expiration before setting `SecurityContextHolder`. If a token is forged, expired, or malformed, parsing fails safely without bubbling 500 errors, and the request is rejected with `401 Unauthorized`.
- **Stateless Session Management:** Configured via `SessionCreationPolicy.STATELESS`, eliminating HTTP session state in memory and enabling horizontal scalability.

### 5.3 Broken Object Level Authorization (BOLA/IDOR) Defense
- **The Vulnerability:** An attacker sends `/api/accounts/1/transactions` or `/api/accounts/deposit` with another user's account ID.
- **The Fix:** The service layer retrieves the authenticated username from `SecurityContextHolder` and verifies:
  ```java
  if (!account.getUser().getUsername().equals(currentUsername)) {
      throw new AccountOwnershipException("Access denied: You do not own account ID " + accountId);
  }
  ```
  Returns `403 Forbidden` if ownership fails.

---

## 6. Transaction Management & Rollback (`@Transactional`)

### 6.1 How Spring Manages Transactions
- Spring uses **AOP Proxies** around `@Transactional` methods.
- When an external caller calls `transferMoney()`, the proxy begins a transaction via `PlatformTransactionManager`.
- If the method completes normally, the proxy issues a `COMMIT`.
- If an unhandled `RuntimeException` or `Error` occurs (or any `Exception` when `rollbackFor = Exception.class` is set), the proxy issues a `ROLLBACK`.

### 6.2 Proxy Gotchas & Self-Invocation
- **The Self-Invocation Problem:** If method `A()` in `AccountService` calls `@Transactional` method `B()` on `this`, the call bypasses the Spring CGLIB/JDK dynamic proxy! Transaction interception will NOT trigger.
- **Our Design:** Controller calls service methods directly, ensuring all entry points pass through Spring's transaction interceptor proxy.

### 6.3 Atomicity in Transfer Operations
A transfer involves 4 database mutations:
1. Sender balance decrease.
2. Recipient balance increase.
3. Account entity persistence.
4. Transaction ledger record insertion.
Wrapping all 4 within a single `@Transactional(rollbackFor = Exception.class)` ensures that if step 4 fails (e.g. database constraint error), steps 1, 2, and 3 are automatically rolled back. No money is ever lost or created out of thin air.

---

## 7. Concurrency Problem & Deadlock-Free Locking

### 7.1 The Race Condition (Lost Updates & Double Spending)
Assume Alice has ₹100. She sends two simultaneous transfer requests of ₹100 each (Thread 1 to Bob, Thread 2 to Charlie):
1. **Thread 1** reads Alice's balance = ₹100.
2. **Thread 2** reads Alice's balance = ₹100.
3. **Thread 1** validates `100 >= 100`, calculates `100 - 100 = 0`, writes balance = ₹0.
4. **Thread 2** validates `100 >= 100`, calculates `100 - 100 = 0`, writes balance = ₹0.
**Result:** Alice transferred ₹200 while only possessing ₹100. This is a classic lost update / double spending vulnerability.

### 7.2 The Solution: Pessimistic Row-Level Locking
In `AccountRepository`:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findByIdWithLock(@Param("id") Long id);
```
In MySQL InnoDB, this issues `SELECT * FROM accounts WHERE id = ? FOR UPDATE`. Thread 2 is blocked by the database engine at the row level until Thread 1 commits. When Thread 2 acquires the lock, it reads the updated balance (₹0), fails the balance check, and throws `InsufficientBalanceException`.

### 7.3 Deadlock Problem & Ascending-Order Lock Strategy
- **Deadlock Scenario:**
  - Alice (Account 1) transfers to Bob (Account 2). Thread A locks Account 1 and attempts to lock Account 2.
  - Bob (Account 2) transfers to Alice (Account 1). Thread B locks Account 2 and attempts to lock Account 1.
  - **Deadlock!** Both threads wait on each other infinitely until MySQL detects the deadlock and aborts one transaction.
- **Implemented Fix (Lock Ordering):**
  We sort account IDs numerically:
  ```java
  Long firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
  Long secondId = fromId.compareTo(toId) < 0 ? toId : fromId;

  Account first = repository.findByIdWithLock(firstId)...;
  Account second = repository.findByIdWithLock(secondId)...;
  ```
  Both Thread A and Thread B are forced to acquire lock on Account 1 first, then Account 2. Circular wait is mathematically eliminated.

---

## 8. Five Key Design Decisions and Their Trade-offs

| Decision | Alternative Considered | Chosen Approach | Trade-off / Why |
|---|---|---|---|
| **1. Concurrency Control** | Optimistic Locking (`@Version`) | Pessimistic Locking (`PESSIMISTIC_WRITE`) | In high-contention financial systems, optimistic locking throws frequent `OptimisticLockingFailureException` requiring client retries. Pessimistic locking blocks cleanly until lock release, guaranteeing linear execution. |
| **2. Deadlock Mitigation** | Retry on Deadlock | Deterministic Lock Ordering (`min/max` ID) | Retrying on deadlock wastes database CPU and connection pool slots. Ordering locks by account ID prevents deadlocks before they happen with zero runtime overhead. |
| **3. Transfer Ledger Storage** | Separate Debit/Credit Records (2 rows) | Single Unified Transfer Entry (1 row) with dynamic display mapping | 2 rows per transfer double table growth and caused a bug where both rows matched the query, showing duplicate entries. A single ledger row cut storage in half and eliminated duplicates. |
| **4. Architecture** | Microservices (Auth Service + Account Service) | Well-structured Monolith | For an educational wallet with a single database, microservices add network latency, distributed transaction complexity (Saga / 2PC), and deployment overhead without practical benefit. |
| **5. Testing Strategy** | Testing only against live MySQL | Embedded H2 for automated CI + MySQL for deployment | Testing against live MySQL requires database daemons running on every developer machine and CI runner. H2 with MySQL compatibility allows fast, isolated 29-test builds in under 35 seconds. |

---

## 9. Top 10 Interview Questions & Model Answers

### Q1: Why did you use `BigDecimal` instead of `double` or `float` for balances?
**Answer:** `float` and `double` are binary floating-point types governed by IEEE 754. They represent fractions as sums of powers of two, which cannot represent numbers like `0.1` or `0.01` exactly. Over time, rounding errors accumulate in financial transactions. `BigDecimal` provides arbitrary-precision signed decimal numbers with explicit rounding modes (`RoundingMode.HALF_UP`), preventing penny-drop errors.

### Q2: What happens if an exception is thrown in the middle of a transfer?
**Answer:** The method is annotated with `@Transactional(rollbackFor = Exception.class)`. If an exception occurs after deducting the sender's balance (e.g. recipient account not found, or database failure during history logging), Spring's transaction interceptor catches the exception and tells the underlying Hibernate/JDBC session to issue a `ROLLBACK`. The sender's balance is restored, and neither account balance changes.

### Q3: How do you prevent a user from spending money they don't have via concurrent requests?
**Answer:** I used JPA Pessimistic Locking (`LockModeType.PESSIMISTIC_WRITE`). When a transfer or withdrawal begins, the account record is fetched using `SELECT ... FOR UPDATE`. This places an exclusive row-level lock on the sender's account in MySQL. Any concurrent request attempting to access that account must wait until the first transaction commits or rolls back, guaranteeing that the balance check reads the up-to-date balance.

### Q4: How does your system prevent database deadlocks during bidirectional transfers?
**Answer:** Deadlocks occur when Thread 1 locks Account A and waits for B, while Thread 2 locks Account B and waits for A. I broke the circular-wait condition by sorting the two account IDs numerically and always locking the account with the smaller ID first, followed by the larger ID. Regardless of who is the sender or receiver, all transactions acquire locks in the exact same order.

### Q5: What is an Idempotency Key, and why is it important in payments?
**Answer:** In distributed systems or mobile apps, a client might send a transfer request, but a network timeout occurs before receiving the response. If the client retries, a naive backend would execute the transfer a second time. An idempotency key is a unique token generated by the client for that specific payment intent. The backend stores this key with a unique constraint. If a retry arrives with the same key, the backend returns the recorded result without re-executing the debit or credit.

### Q6: How does your daily transfer limit work?
**Answer:** Rather than simply checking if the single transfer amount is under ₹10,000, our system calculates the cumulative sum of all successful transfers initiated by that account since UTC midnight (`00:00:00 UTC`). It runs an index-backed aggregation query: `SELECT SUM(amount) FROM transaction_history WHERE sender_id = :id AND type = 'TRANSFER' AND status = 'SUCCESS' AND created_at >= :startOfDay`. If `currentTotal + requestedAmount > limit`, the transaction is rejected.

### Q7: What is Broken Object Level Authorization (BOLA), and how did you fix it?
**Answer:** BOLA (formerly IDOR) occurs when an endpoint takes an object ID (like `/api/accounts/{id}/transactions` or `deposit?id=...`) and accesses the resource without checking if the requester owns it. I secured this by extracting the authenticated username from Spring's `SecurityContextHolder` and verifying that `account.getUser().getUsername().equals(currentUsername)`. If they don't match, the request immediately terminates with `403 Forbidden`.

### Q8: Why use stateless JWT authentication instead of HTTP sessions?
**Answer:** In a traditional session-based architecture, the server stores session state in memory (or sticky sessions / Redis). With stateless JWTs, the user identity and claims are cryptographically signed inside the token itself. The server only needs to verify the signature using the secret key. This reduces server memory usage and allows frictionless horizontal scaling behind a load balancer.

### Q9: What happens if a JWT token is expired or malformed?
**Answer:** In our `JwtFilter`, token parsing is wrapped in safe exception handling catching `JwtException` and `IllegalArgumentException`. Instead of letting unhandled exceptions bubble up to a 500 error, the filter leaves the Spring `SecurityContext` empty. Spring Security's custom `AuthenticationEntryPoint` then intercepts the unauthenticated request and returns a structured `401 Unauthorized` JSON response.

### Q10: How did you test concurrency and transactional rollback?
**Answer:** I wrote integration tests using JUnit 5. For concurrency, I used `ExecutorService` and `CountDownLatch` to trigger 10 threads transferring ₹50 simultaneously from an account with ₹1,000. The test verifies that the final sender balance is exactly ₹500, receiver balance is ₹1,000, and the total system invariant (`sender + receiver = 1500`) holds with zero lost updates. For rollback, I tested invalid recipient and insufficient balance scenarios, asserting that neither balance changed and zero orphaned records were created.

---

## 10. Clear List of Features Not Implemented (Honest Technical Scope)

To speak honestly during technical interviews, be transparent about architectural boundaries:
1. **Distributed Two-Phase Commit (2PC) / Saga:** Because this is a monolithic application with a single MySQL database, local ACID transactions with row-level locks are used rather than distributed Saga patterns.
2. **User-Configurable Timezone for Daily Limit:** The daily limit resets strictly at UTC midnight (`00:00:00 UTC`), rather than localized to each user's regional timezone.
3. **Multi-Currency & FX Exchange:** The system operates with single-currency precision and does not support real-time foreign exchange conversions.
4. **Hardware Security Module (HSM) / KMS:** Secrets are loaded via environment variables rather than a dedicated cloud secrets manager like AWS Secrets Manager or HashiCorp Vault.
5. **Rate Limiting / Anti-Automation:** While passwords and tokens are secure, DDoS protection and IP-based rate limiting (e.g. via Redis Token Bucket or Cloudflare) are omitted from this application layer.
