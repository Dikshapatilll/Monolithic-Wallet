# Security and Code Audit Report

**Project:** Monolith Wallet System
**Audit Date:** October 2026
**Auditor:** Senior Java Backend & Security Specialist
**Target:** IDeaS Revenue Solutions SDE Intern Preparation

---

## 1. Executive Summary

An in-depth security, transaction, database, and architectural audit was performed on the Monolith Wallet System codebase (`com.wallet.wallet`).

While the application establishes a foundational structure using Spring Boot 3.2.5, Spring Security, Spring Data JPA, Hibernate, MySQL, and JJWT (0.11.5), the codebase contains multiple **Critical** and **High** vulnerabilities, including:
- Completely unauthenticated account and financial endpoints (`/api/accounts/**` permitted to all).
- Broken Object Level Authorization (BOLA/IDOR) on transaction history and deposits.
- Concurrency race conditions resulting in lost updates and balance overspending under concurrent transfers.
- Severe transaction history double-reporting bug where every transfer appears twice in both accounts.
- Ineffective "daily transfer limit" that checks only single transaction amounts without aggregating daily volume.
- Hardcoded database credentials and JWT signing secret in tracked source files.
- Lack of transaction boundaries (`@Transactional`) on deposit and withdrawal operations.
- Build and test failures out-of-the-box due to unconfigured test environments relying on live MySQL.

This document details all findings categorized by severity, explains their architectural and business impact, and proposes minimal, robust remedies.

---

## 2. Architecture & Request Flow

### 2.1 Component Structure
The project adopts a standard 3-tier monolithic Spring Boot architecture:
1. **Controller Layer:**
   - `AuthController`: Handles `/api/auth/register` and `/api/auth/login`. Returns raw strings (`"User registered!"`, JWT token) rather than standardized DTO responses. Does not enforce `@Valid`.
   - `AccountController`: Exposes `/api/accounts/create`, `/deposit`, `/transfer`, `/withdraw`, `/{id}/transactions`, `/me`. Relies on `@RequestParam` query parameters instead of validated request DTOs.
2. **Service Layer:**
   - `AuthService`: Manages user registration and password hashing using BCrypt. Issues JWTs via `JwtUtil`.
   - `AccountService`: Contains core business logic for accounts, deposits, withdrawals, transfers, and transaction history.
3. **Repository Layer:**
   - `AppUserRepository` and `UserRepository`: Redundant interfaces for `AppUser`.
   - `AccountRepository`: Provides queries for user accounts.
   - `TransactionHistoryRepository`: Provides pagination queries for transaction logs.
4. **Security Layer:**
   - `SecurityConfig`: Configures HTTP security filter chain.
   - `JwtFilter`: Extracts bearer token and attempts authentication.
   - `JwtUtil`: Generates and parses HMAC-SHA signed JWT tokens.
5. **Database & Persistence:**
   - Entities: `AppUser`, `Account`, `TransactionHistory`. Enums: `TransactionType`, `TransactionStatus`.
   - Target database: MySQL with Hibernate `ddl-auto=update`.

### 2.2 Request Flow & Gaps
```
[Client]
   │
   ▼
[SecurityFilterChain] ──> Permits /api/accounts/** without JWT verification! (CRITICAL)
   │
   ▼
[JwtFilter] ──> Extracts Bearer token; missing error handling for expired/malformed tokens
   │
   ▼
[Controller] ──> Bypasses DTO validation (@Valid); binds raw query params
   │
   ▼
[Service] ──> Missing @Transactional on deposit/withdraw; no concurrency locking;
   │          no sender-ownership check on account IDs; pseudo-daily limit
   ▼
[Database (MySQL)] ──> Missing precision/scale on amounts; missing indexes on history foreign IDs
```

---

## 3. Detailed Audit Findings Matrix

| ID | Category | Vulnerability / Issue | Severity | Impact |
|---|---|---|---|---|
| SEC-01 | Security | `/api/accounts/**` configured as `permitAll()` | **CRITICAL** | Any unauthenticated user can call account endpoints. |
| SEC-02 | Authorization | Missing Account Ownership Checks (BOLA/IDOR) | **CRITICAL** | Any user can view other users' transaction history by ID. |
| CON-01 | Concurrency | No Lock on Accounts During Transfers/Withdrawals | **CRITICAL** | Race condition enables double spending and negative balances. |
| TX-01 | Transaction | Missing `@Transactional` on Deposit and Withdraw | **HIGH** | Inconsistent state if balance update succeeds but history log fails. |
| TX-02 | Correctness | Self-Transfer Not Prevented | **HIGH** | User can transfer to self, leading to state corruption and phantom fees. |
| TX-03 | Reporting | Transaction History Duplication Bug | **HIGH** | Transfer creates inverted sender/receiver logs causing duplicate records. |
| BIZ-01 | Business Logic | Daily Transfer Limit does not aggregate daily transfers | **HIGH** | Only checks single transfer amount; users can bypass limit with multiple requests. |
| CFG-01 | Configuration | Hardcoded Database Credentials and JWT Secret | **HIGH** | Sensitive secrets committed to version control. |
| API-01 | API Design | Missing Request DTOs and Bean Validation | **MEDIUM** | Query params used instead of DTOs; invalid amounts bypass controller checks. |
| SEC-03 | Security | User Enumeration via Differentiated Error Messages | **MEDIUM** | Attacker can determine valid usernames through error messages. |
| DB-01 | Database | Missing Column Precision & Indexes on Foreign Keys | **MEDIUM** | Inexact monetary decimals; full table scans on transaction history. |
| SEC-04 | Security | Unhandled JWT Parsing Exceptions in Filter | **MEDIUM** | Expired or corrupted tokens cause unhandled 500 errors instead of clean 401. |
| TST-01 | Testing | Zero Functional Tests & Failing Test Suite | **HIGH** | Build fails out of the box because tests require a live MySQL server. |
| ARC-01 | Code Quality | Duplicate Repositories (`UserRepository` & `AppUserRepository`) | **LOW** | Architectural confusion and maintenance overhead. |

---

## 4. Deep-Dive Analysis of Vulnerabilities

### 4.1 SEC-01: Publicly Exposed Account Endpoints (CRITICAL)
- **Location:** `SecurityConfig.java` (Line 24)
- **Description:**
  ```java
  .requestMatchers("/api/auth/**",
                   "/swagger-ui/**",
                   "/api/accounts/**",
                   "/v3/api-docs/**",
                   "swagger-ui.html"
  ).permitAll()
  ```
  The wildcard `/api/accounts/**` is explicitly added to `permitAll()`.
- **Impact:** Anyone on the internet can call `/api/accounts/deposit?id=1&amount=1000` or `/api/accounts/1/transactions` without providing an authentication token. While methods using `SecurityContextHolder` fail with null pointer or anonymous user exceptions, unauthenticated users can still invoke any method that doesn't inspect security context.
- **Minimal Fix:** Remove `/api/accounts/**` from `permitAll()`. Ensure only `/api/auth/**`, Swagger UI, and OpenAPI docs are public.

### 4.2 SEC-02: Missing Account Ownership Checks / BOLA (CRITICAL)
- **Location:** `AccountController.java` (`getTransactions`), `AccountService.java` (`getHistory`, `deposit`)
- **Description:**
  In `getTransactions(@PathVariable Long id, ...)`, the client provides an arbitrary account `id`. In `AccountService.getHistory(accountId, pageable)`, the repository query is executed directly:
  ```java
  historyRepo.findBySenderIdOrReceiverId(accountId, accountId, pageable)
  ```
  The service never checks whether the authenticated user owns `accountId`.
- **Impact:** Any authenticated user who knows or guesses another user's account ID can view their entire financial transaction history, violating confidentiality.
- **Minimal Fix:** Fetch the authenticated user from `SecurityContextHolder`, verify that the target account belongs to the authenticated user, or reject the request with HTTP 403 Forbidden.

### 4.3 CON-01: Concurrency and Race Condition in Transfers & Withdrawals (CRITICAL)
- **Location:** `AccountService.java` (`transferMoney`, `withdraw`)
- **Description:**
  `Account` has no `@Version` field (optimistic locking), and `AccountRepository` uses default reads without `PESSIMISTIC_WRITE` locks.
  Consider concurrent transfers:
  1. Thread A reads Account 1 balance = $100.
  2. Thread B reads Account 1 balance = $100.
  3. Thread A validates $100 >= $100, sets balance to $0, and saves.
  4. Thread B validates $100 >= $100, sets balance to $0, and saves.
  5. Both transfers of $100 succeed. $200 has left Account 1, though it only held $100.
- **Impact:** Double spending, negative balances, and severe financial ledger corruption.
- **Minimal Fix:** Implement pessimistic row-level locking (`PESSIMISTIC_WRITE`) or optimistic locking with `@Version`. When acquiring pessimistic locks on multiple accounts during a transfer, acquire them in a consistent order (e.g. by ascending account ID: `firstLock = min(fromId, toId); secondLock = max(fromId, toId)`) to prevent deadlocks.

### 4.4 TX-01: Incomplete Transaction Demarcation (HIGH)
- **Location:** `AccountService.java` (`deposit`, `withdraw`)
- **Description:**
  `deposit` and `withdraw` perform both a balance modification (`repository.save(account)`) and a history log insertion (`historyRepo.save(...)`), yet neither method has `@Transactional`.
- **Impact:** If the history write fails (e.g., database constraint violation, connection drop), the balance change is committed to the database without a transaction history record, leading to untraceable balance discrepancies.
- **Minimal Fix:** Annotate `deposit`, `withdraw`, and `createAccount` with `@Transactional`.

### 4.5 TX-02: Unchecked Self-Transfers (HIGH)
- **Location:** `AccountService.java` (`transferMoney`)
- **Description:**
  The service does not check if `sender.getId().equals(receiver.getId())`. If a user initiates a transfer to their own account ID:
  - `sender` and `receiver` refer to the same record.
  - Hibernate entity state becomes inconsistent (debiting and crediting the same entity or overwriting changes).
  - Two transaction records (TRANSFER_OUT and TRANSFER_IN) are inserted for the same account.
- **Impact:** Corrupted balances or misleading audit trail.
- **Minimal Fix:** Check `if (sender.getId().equals(receiver.getId())) throw new IllegalArgumentException("Self-transfers are not permitted");`

### 4.6 TX-03: Transaction History Duplication Bug (HIGH)
- **Location:** `AccountService.java` (lines 138–154) and `TransactionHistoryRepository.java` (line 11)
- **Description:**
  In `transferMoney`:
  ```java
  // Sender history
  historyRepo.save(new TransactionHistory(sender.getId(), receiver.getId(), amount, TransactionType.TRANSFER_OUT, TransactionStatus.SUCCESS));
  // Receiver history
  historyRepo.save(new TransactionHistory(receiver.getId(), sender.getId(), amount, TransactionType.TRANSFER_IN, TransactionStatus.SUCCESS));
  ```
  And in `getHistory`:
  ```java
  historyRepo.findBySenderIdOrReceiverId(accountId, accountId, pageable);
  ```
  When Account 1 views history, SQL runs `WHERE sender_id = 1 OR receiver_id = 1`.
  - For Record 1: `sender_id = 1` -> matches! (shows TRANSFER_OUT)
  - For Record 2: `receiver_id = 1` -> matches! (shows TRANSFER_IN)
  Both records match for Account 1. Account 1 sees BOTH a transfer-out of $50 and a transfer-in of $50 for the exact same transaction!
- **Impact:** Misleading user balances, incorrect statements, and accounting confusion.
- **Minimal Fix:** Either save a single ledger entry with `senderId` and `receiverId` and map the type dynamically based on whether the inquiring account is the sender (TRANSFER_OUT) or receiver (TRANSFER_IN), OR keep separate records tied directly to the owning account (e.g. `accountId`, `relatedAccountId`, `type`).

### 4.7 BIZ-01: Pseudo-Daily Transfer Limit (HIGH)
- **Location:** `AccountService.java` (lines 31, 127–129)
- **Description:**
  `AccountService` checks:
  ```java
  if (amount.compareTo(DAILY_TRANSFER_LIMIT) > 0) {
      throw new RuntimeException("Daily transfer limit exceeded");
  }
  ```
  It compares only the **single current transfer amount** against $10,000. It does NOT calculate or aggregate the sum of transfers sent by the user during the current day.
- **Impact:** A user can make twenty $9,999 transfers in a single day ($199,980 total) without triggering the limit. Calling this a "daily limit" in code and interview explanations is incorrect.
- **Minimal Fix:** Either query `TransactionHistoryRepository` for the sum of successful `TRANSFER_OUT` transactions initiated by the sender account between start of today (UTC or application timezone) and now, or clearly document and rename this as a `SINGLE_TRANSFER_LIMIT`. To support real daily limits, add an aggregation query: `findTotalTransferredSince(senderId, startOfDay)`.

### 4.8 CFG-01: Hardcoded Secrets in Tracked Source Files (HIGH)
- **Location:** `application.properties` (lines 5-6), `JwtUtil.java` (line 14)
- **Description:**
  - Database username and password are saved as plaintext in `src/main/resources/application.properties`.
  - `JwtUtil.java` defines `private final String SECRET = "mysecretkeymysecretkeymysecretkeymysecretkey";`.
- **Impact:** Anyone with read access to the Git repository obtains database credentials and can forge valid JWT tokens for any user.
- **Minimal Fix:**
  - Parameterize `application.properties` with environment variables (e.g. `${DB_URL:...}`, `${DB_USERNAME:...}`, `${DB_PASSWORD:...}`, `${JWT_SECRET:...}`).
  - Provide a safe `.env.example` file with non-sensitive placeholder values.
  - Inject JWT secret into `JwtUtil` using Spring's `@Value("${jwt.secret}")`.
  - Ensure `.env` is listed in `.gitignore`.

### 4.9 API-01: Missing Request DTOs and Validation in Controllers (MEDIUM)
- **Location:** `AccountController.java`, `AuthController.java`
- **Description:**
  - In `AccountController`, methods use `@RequestParam` instead of `@RequestBody` with Jakarta Bean Validation (`@Valid`, `@NotNull`, `@Positive`, `@DecimalMin`).
  - Negative amounts can be passed; `deposit` and `transferMoney` lack complete null checks.
  - In `AuthController`, `register` and `login` accept `AuthRequest` without `@Valid`.
  - Passwords with empty strings (`""`) can be registered and hashed.
- **Impact:** Uncontrolled input, poor error messages, HTTP 500 exceptions, and malformed entity records.
- **Minimal Fix:** Create proper request DTOs (e.g., `DepositRequest`, `TransferRequest`, `WithdrawRequest`), annotate them with `@NotNull`, `@Positive`, `@DecimalMin("0.01")`, and annotate controller parameters with `@Valid`.

### 4.10 SEC-03: User Enumeration Vulnerability (MEDIUM)
- **Location:** `AuthService.java` (lines 41-46)
- **Description:**
  ```java
  AppUser user = repo.findByUsername(req.getUsername())
          .orElseThrow(() -> new RuntimeException("User not found"));

  if(!encoder.matches(req.getPassword(), user.getPassword())) {
      throw new RuntimeException("Invlaid Password");
  }
  ```
- **Impact:** An attacker can enumerate registered usernames by checking whether the API returns "User not found" vs "Invlaid Password".
- **Minimal Fix:** Return a generic "Invalid username or password" for both missing users and mismatched passwords.

### 4.11 DB-01: Missing Column Precision, Constraints, and Indexes (MEDIUM)
- **Location:** `Account.java`, `TransactionHistory.java`
- **Description:**
  - `balance` in `Account` and `amount` in `TransactionHistory` are `BigDecimal` fields without explicit column definitions. In MySQL, JPA default mappings can vary and lack check constraints (`balance >= 0`).
  - In `TransactionHistory`, `senderId` and `receiverId` are bare `Long` columns without database indexes. As transaction history grows to tens of thousands of rows, every call to `/api/accounts/{id}/transactions` performs a sequential table scan.
- **Impact:** Slow queries on transaction history, potential rounding or scale inconsistencies.
- **Minimal Fix:** Add `@Column(precision = 19, scale = 2, nullable = false)` to monetary amounts and add table indexes on `(sender_id, created_at)` and `(receiver_id, created_at)`.

### 4.12 SEC-04: Unhandled Token Exceptions in JwtFilter (MEDIUM)
- **Location:** `JwtFilter.java` (lines 35-40)
- **Description:**
  `jwtUtil.extractUsername(token)` throws `ExpiredJwtException`, `SignatureException`, or `MalformedJwtException` when given an invalid token. In `JwtFilter`, there is no `try-catch` block around token parsing.
- **Impact:** The filter throws an uncaught exception during request processing, causing an unhandled HTTP 500 error instead of a standard HTTP 401 Unauthorized.
- **Minimal Fix:** Wrap token parsing in a `try-catch` block; if parsing fails, leave the security context unauthenticated and permit the filter chain to continue so Spring Security handles the rejection or write an explicit 401 JSON response.

### 4.13 TST-01: Zero Functional Test Coverage & Failing Test Suite (HIGH)
- **Location:** `WalletApplicationTests.java`
- **Description:**
  Only one test exists (`contextLoads`), and it fails immediately if a MySQL database is not running on `localhost:3306` with specific credentials.
- **Impact:** The CI/CD pipeline and local build cannot verify business logic, security rules, rollback behavior, or concurrency safeguards.
- **Minimal Fix:** Configure H2 in-memory database dependency in `test` scope, add an `application-test.properties` profile for isolated fast automated tests, and create comprehensive JUnit 5 and MockMvc tests covering auth, transfers, concurrency, validation, and rollback.

### 4.14 ARC-01: Duplicate User Repositories (LOW)
- **Location:** `UserRepository.java` and `AppUserRepository.java`
- **Description:**
  Two identical repository interfaces for `AppUser` are defined and used interchangeably across `AuthService` and `AccountService`.
- **Impact:** Unnecessary confusion and duplicated maintenance.
- **Minimal Fix:** Consolidate to `UserRepository`.

---

## 5. Remediation Roadmap

The remediation will be executed systematically across the following phases:

1. **Phase B — Security and Configuration:**
   - Parameterize database and JWT configuration; create safe `.env.example` and update `.gitignore`.
   - Secure all `/api/accounts/**` endpoints in `SecurityConfig`.
   - Fix `JwtFilter` and `JwtUtil` error handling and secret loading.
   - Enforce account ownership verification on all account access.
   - Introduce request DTOs with Jakarta Bean Validation and `@Valid`.
   - Prevent user enumeration in authentication.

2. **Phase C — Transaction Correctness & Concurrency:**
   - Ensure all balance-mutating operations (`deposit`, `withdraw`, `transfer`) are `@Transactional`.
   - Prevent self-transfers.
   - Implement pessimistic row-level locking with consistent lock ordering to eliminate lost updates and prevent deadlocks.
   - Fix the transaction history recording and querying bug to prevent duplicate entries.
   - Implement true daily transfer aggregation limit with clear time window and documentation.
   - Add idempotency support for transfers.

3. **Phase D — Database and API Quality:**
   - Define exact `DECIMAL(19, 2)` column precision for all monetary amounts.
   - Add database indexes for `TransactionHistory` lookups and pagination.
   - Add clean DTO responses and standardized error envelopes.
   - Consolidate duplicate repositories.

4. **Phase E — Automated Testing:**
   - Add H2 test profile for reliable, isolated builds.
   - Implement unit and integration tests for authentication, authorization, ownership, deposits, withdrawals, transfers, rollback, and concurrency.

5. **Phase F & G — Documentation & Interview Readiness:**
   - Update `README.md`.
   - Create `docs/INTERVIEW_GUIDE.md` tailored for IDeaS Revenue Solutions interview preparation.
   - Verify all tests pass cleanly.
