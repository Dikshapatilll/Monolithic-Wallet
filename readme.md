# Monolith Wallet System

A hardened, educational digital wallet backend application built with **Spring Boot 3**, **Spring Security**, **Spring Data JPA & Hibernate**, **MySQL**, and **JWT authentication**.

This system implements registration, authentication, multi-account management, deposits, withdrawals, secure transfers with deadlock-free pessimistic locking, idempotency guarantees, daily transfer volume limits, and paginated audit transaction logs.

---

## 1. Architecture Overview

The system uses a classic 3-tier monolithic architectural pattern:

```
[ HTTP Clients / Swagger UI ]
              │
              ▼
   ┌───────────────────────┐
   │    Security Filter    │  (JwtFilter: validates tokens, populates SecurityContext)
   └──────────┬────────────┘
              ▼
   ┌───────────────────────┐
   │   Controller Layer    │  (AuthController, AccountController: validates DTOs via @Valid)
   └──────────┬────────────┘
              ▼
   ┌───────────────────────┐
   │     Service Layer     │  (AuthService, AccountService: business logic, @Transactional)
   └──────────┬────────────┘
              ▼
   ┌───────────────────────┐
   │   Repository Layer    │  (Spring Data JPA: Pessimistic Locks, Index-backed Queries)
   └──────────┬────────────┘
              ▼
   ┌───────────────────────┐
   │    Database (MySQL)   │  (InnoDB: row-level locking, ACID transactions)
   └───────────────────────┘
```

---

## 2. Technology Stack

- **Language:** Java 17 / 21
- **Framework:** Spring Boot 3.2.5
- **Security:** Spring Security 6 (Stateless JWT + BCrypt)
- **Data Access:** Spring Data JPA, Hibernate 6 ORM
- **Database:** MySQL 8.x (Production/Local), H2 In-Memory (Automated Testing)
- **Validation:** Jakarta Bean Validation (Hibernate Validator)
- **API Documentation:** SpringDoc OpenAPI 2.5.0 (Swagger UI)
- **Build Tool:** Maven 3.x (with bundled wrapper `./mvnw`)
- **Testing:** JUnit 5, MockMvc, Mockito

---

## 3. Security Highlights

1. **Restricted Protected Endpoints:**
   - Public: `/api/auth/register`, `/api/auth/login`, `/swagger-ui/**`, `/v3/api-docs/**`.
   - Strictly Authenticated: All `/api/accounts/**` endpoints require a valid JWT Bearer token.
2. **Broken Object Level Authorization (BOLA/IDOR) Prevention:**
   - Every account modification (deposit, withdraw, transfer) and transaction history query checks account ownership against `SecurityContextHolder.getContext().getAuthentication().getPrincipal()`.
   - Access to unowned accounts returns `403 Forbidden`.
3. **Safe Secrets & Zero Hardcoding:**
   - Database credentials and JWT signing secret are parameterized via environment variables with safe development defaults.
   - A `.env.example` template is provided. Real `.env` files are ignored in `.gitignore`.
4. **Password Security:**
   - Passwords hashed with `BCryptPasswordEncoder` (work factor 10).
   - Passwords are never serialized in responses (`@JsonIgnore`) or printed in logs.
   - Timing/enumeration attacks mitigated: `/api/auth/login` returns uniform `"Invalid username or password"`.
5. **Robust JWT Validation:**
   - `JwtFilter` handles missing, invalid, and expired tokens gracefully, rejecting unauthenticated requests with clean `401 Unauthorized` JSON.

---

## 4. Transaction Correctness & Concurrency

### 4.1 Pessimistic Row-Level Locking
To prevent lost updates and race-condition double spending (e.g., two concurrent transfers spending the same $100 balance), account rows are locked using JPA `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE` in MySQL InnoDB):
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findByIdWithLock(@Param("id") Long id);
```

### 4.2 Deadlock Prevention via Consistent Lock Ordering
If User A transfers to User B while User B concurrently transfers to User A, locking accounts in caller-specified order causes deadlocks.

The application sorts account IDs in ascending numerical order (`min(fromId, toId)` locked first, `max(fromId, toId)` locked second):
```java
Long firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
Long secondId = fromId.compareTo(toId) < 0 ? toId : fromId;

Account first = repository.findByIdWithLock(firstId)...;
Account second = repository.findByIdWithLock(secondId)...;
```
This guarantees an identical locking sequence across all concurrent threads, eliminating circular wait conditions.

### 4.3 Single-Ledger History & Deduplication
Transfers insert **one** unified record in `transaction_history` with `type = TRANSFER`. When queried:
- Sender views the record mapped as `TRANSFER_OUT`.
- Recipient views the record mapped as `TRANSFER_IN`.
- Eliminates duplicate entries.

### 4.4 Cumulative Daily Transfer Limit
The daily transfer limit (`wallet.transfer.daily-limit`, default ₹10,000) aggregates all successful transfers initiated by the sender since UTC midnight (`00:00:00 UTC`):
```sql
SELECT COALESCE(SUM(t.amount), 0) FROM transaction_history t
WHERE t.sender_id = :senderId AND t.type = 'TRANSFER' AND t.status = 'SUCCESS' AND t.created_at >= :startOfDay;
```

### 4.5 Idempotency Protection
Transfers accept an optional `idempotencyKey`. If a client retries due to network failure, the existing transaction response is safely replayed without double-debiting.

---

## 5. Prerequisites & Setup

### Prerequisites
- Java Development Kit (JDK) 17 or 21
- MySQL Server 8.x (optional for development; tests run on embedded H2)

### Environment Configuration
Copy the template file to configure your local environment:
```bash
cp .env.example .env
```
Edit `.env` (or set environment variables):
```properties
DB_URL=jdbc:mysql://localhost:3306/wallet?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password
JWT_SECRET=YourSuperSecretKeyAtLeast32BytesLongForSecurity2026!
JWT_EXPIRATION_MS=86400000
PORT=8080
```

### Build & Test
Run the full automated test suite (uses embedded H2 test profile):
```bash
cd wallet
./mvnw clean test
```

### Run Application
```bash
cd wallet
./mvnw spring-boot:run
```
The application starts at `http://localhost:8080`.
Swagger UI is accessible at `http://localhost:8080/swagger-ui.html`.

---

## 6. API Reference

### 6.1 Authentication (`/api/auth`)

#### Register User
`POST /api/auth/register`
```json
{
  "username": "alice",
  "password": "Password123"
}
```
*Response (201 Created):*
```json
{
  "message": "User registered successfully",
  "token": null,
  "username": null
}
```

#### Login User
`POST /api/auth/login`
```json
{
  "username": "alice",
  "password": "Password123"
}
```
*Response (200 OK):*
```json
{
  "message": "Authentication successful",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "alice"
}
```

---

### 6.2 Accounts (`/api/accounts`) — Requires `Authorization: Bearer <token>`

#### Create Account
`POST /api/accounts/create`
```json
{
  "balance": 1000.00
}
```
*Response (201 Created):*
```json
{
  "id": 1,
  "username": "alice",
  "balance": 1000.00
}
```

#### Deposit Money
`POST /api/accounts/deposit`
```json
{
  "accountId": 1,
  "amount": 250.00
}
```
*Response (200 OK):*
```json
{
  "id": 1,
  "username": "alice",
  "balance": 1250.00
}
```

#### Withdraw Money
`POST /api/accounts/withdraw`
```json
{
  "accountId": 1,
  "amount": 100.00
}
```
*Response (200 OK):*
```json
{
  "id": 1,
  "username": "alice",
  "balance": 1150.00
}
```

#### Transfer Money
`POST /api/accounts/transfer`
```json
{
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 300.00,
  "idempotencyKey": "txn-uuid-98765"
}
```
*Response (200 OK):*
```json
{
  "id": 14,
  "senderId": 1,
  "receiverId": 2,
  "amount": 300.00,
  "createdAt": "2026-10-23T15:54:20",
  "type": "TRANSFER_OUT",
  "status": "SUCCESS",
  "idempotencyKey": "txn-uuid-98765"
}
```

#### Paginated Transaction History
`GET /api/accounts/1/transactions?page=0&size=10`
*Response (200 OK):*
```json
{
  "content": [
    {
      "id": 14,
      "senderId": 1,
      "receiverId": 2,
      "amount": 300.00,
      "createdAt": "2026-10-23T15:54:20",
      "type": "TRANSFER_OUT",
      "status": "SUCCESS",
      "idempotencyKey": "txn-uuid-98765"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10
  },
  "totalElements": 1,
  "totalPages": 1
}
```

#### Get Current User Accounts
`GET /api/accounts/me`
`GET /api/accounts`

---

## 7. Known Limitations & Future Improvements

1. **Two-Phase Commit / Outbox Pattern:** In a distributed or multi-service architecture, events would be published using the Transactional Outbox pattern rather than local in-database writes.
2. **Dynamic Daily Limit Reset:** The daily transfer limit resets at `00:00:00 UTC`. A configurable user timezone could be introduced in user settings.
3. **Multi-Currency Support:** The current application uses a single implicit currency (e.g. INR or USD). Future iterations can store ISO-4217 currency codes with exchange rate conversion.
4. **Rate Limiting:** Integrating bucket4j or Redis token bucket to protect against high-frequency brute-force login attempts.
