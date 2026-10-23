package com.wallet.wallet.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wallet.wallet.dto.AccountResponse;
import com.wallet.wallet.dto.CreateAccountRequest;
import com.wallet.wallet.dto.DepositRequest;
import com.wallet.wallet.dto.TransactionResponse;
import com.wallet.wallet.dto.TransferRequest;
import com.wallet.wallet.dto.WithdrawRequest;
import com.wallet.wallet.service.AccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts", description = "Endpoints for managing accounts, balances, transfers, and transactions")
@SecurityRequirement(name = "BearerAuth")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    // Create Account (supports JSON body or query param)
    @Operation(summary = "Create a new wallet account for the authenticated user")
    @PostMapping("/create")
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody(required = false) CreateAccountRequest request,
            @RequestParam(required = false) BigDecimal balance) {

        BigDecimal initialBalance = (request != null && request.getBalance() != null)
                ? request.getBalance()
                : balance;

        AccountResponse response = service.createAccount(initialBalance);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Deposit Money (supports JSON body or query params)
    @Operation(summary = "Deposit money into user's account")
    @PostMapping("/deposit")
    public ResponseEntity<AccountResponse> deposit(
            @Valid @RequestBody(required = false) DepositRequest request,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) BigDecimal amount) {

        Long targetId = (request != null && request.getAccountId() != null) ? request.getAccountId() : id;
        BigDecimal targetAmount = (request != null && request.getAmount() != null) ? request.getAmount() : amount;

        AccountResponse response = service.deposit(targetId, targetAmount);
        return ResponseEntity.ok(response);
    }

    // Transfer Money (supports JSON body or query params)
    @Operation(summary = "Transfer money from sender's account to recipient's account")
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transferMoney(
            @Valid @RequestBody(required = false) TransferRequest request,
            @RequestParam(required = false) Long fromId,
            @RequestParam(required = false) Long toId,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String idempotencyKey) {

        Long senderId = (request != null && request.getFromAccountId() != null) ? request.getFromAccountId() : fromId;
        Long recipientId = (request != null && request.getToAccountId() != null) ? request.getToAccountId() : toId;
        BigDecimal transferAmount = (request != null && request.getAmount() != null) ? request.getAmount() : amount;
        String key = (request != null && request.getIdempotencyKey() != null) ? request.getIdempotencyKey() : idempotencyKey;

        TransactionResponse response = service.transferMoney(senderId, recipientId, transferAmount, key);
        return ResponseEntity.ok(response);
    }

    // Withdraw Money (supports JSON body or query params)
    @Operation(summary = "Withdraw money from user's account")
    @PostMapping("/withdraw")
    public ResponseEntity<AccountResponse> withdraw(
            @Valid @RequestBody(required = false) WithdrawRequest request,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) BigDecimal amount) {

        Long targetId = (request != null && request.getAccountId() != null) ? request.getAccountId() : accountId;
        BigDecimal targetAmount = (request != null && request.getAmount() != null) ? request.getAmount() : amount;

        AccountResponse response = service.withdraw(targetId, targetAmount);
        return ResponseEntity.ok(response);
    }

    // Transaction History with Pagination & Ownership Verification
    @Operation(summary = "Get paginated transaction history for an account")
    @GetMapping("/{id}/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        // Enforce maximum page size to prevent denial of service
        int clampedSize = Math.min(Math.max(size, 1), 100);
        int clampedPage = Math.max(page, 0);

        Page<TransactionResponse> history = service.getHistory(
                id,
                PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return ResponseEntity.ok(history);
    }

    // Get My Primary Account
    @Operation(summary = "Get authenticated user's primary/latest account")
    @GetMapping("/me")
    public ResponseEntity<AccountResponse> getMyAccount() {
        return ResponseEntity.ok(service.getMyAccount());
    }

    // Get All Accounts for Current User
    @Operation(summary = "Get all accounts owned by the authenticated user")
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAllMyAccounts() {
        return ResponseEntity.ok(service.getMyAccounts());
    }
}
