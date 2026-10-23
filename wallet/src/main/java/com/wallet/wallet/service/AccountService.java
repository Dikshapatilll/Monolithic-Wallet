package com.wallet.wallet.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.wallet.wallet.dto.AccountResponse;
import com.wallet.wallet.dto.TransactionResponse;
import com.wallet.wallet.entity.Account;
import com.wallet.wallet.entity.AppUser;
import com.wallet.wallet.entity.TransactionHistory;
import com.wallet.wallet.entity.TransactionStatus;
import com.wallet.wallet.entity.TransactionType;
import com.wallet.wallet.exception.AccountOwnershipException;
import com.wallet.wallet.exception.InsufficientBalanceException;
import com.wallet.wallet.exception.ResourceNotFoundException;
import com.wallet.wallet.exception.TransferLimitExceededException;
import com.wallet.wallet.repository.AccountRepository;
import com.wallet.wallet.repository.TransactionHistoryRepository;
import com.wallet.wallet.repository.UserRepository;

@Service
public class AccountService {

    private final AccountRepository repository;
    private final TransactionHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final BigDecimal dailyTransferLimit;

    public AccountService(
            AccountRepository repository,
            TransactionHistoryRepository historyRepo,
            UserRepository userRepo,
            @Value("${wallet.transfer.daily-limit:10000.00}") BigDecimal dailyTransferLimit) {
        this.repository = repository;
        this.historyRepo = historyRepo;
        this.userRepo = userRepo;
        this.dailyTransferLimit = dailyTransferLimit;
    }

    // Helper: Extract username from SecurityContextHolder
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BadCredentialsException("User is not authenticated");
        }
        return (String) auth.getPrincipal();
    }

    // Helper: Verify account ownership
    private Account verifyAccountOwnership(Long accountId, String username) {
        Account account = repository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account with ID " + accountId + " not found"));

        if (account.getUser() == null || !account.getUser().getUsername().equals(username)) {
            throw new AccountOwnershipException("Access denied: You do not own account ID " + accountId);
        }
        return account;
    }

    // Create Account
    @Transactional
    public AccountResponse createAccount(BigDecimal initialBalance) {
        String username = getCurrentUsername();

        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        if (initialBalance == null) {
            initialBalance = BigDecimal.ZERO;
        } else if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative");
        }

        BigDecimal scaledBalance = initialBalance.setScale(2, RoundingMode.HALF_UP);

        Account acc = new Account();
        acc.setBalance(scaledBalance);
        acc.setUsername(username);
        acc.setUser(user);

        repository.save(acc);

        return mapToResponse(acc);
    }

    // Deposit Money
    @Transactional(rollbackFor = Exception.class)
    public AccountResponse deposit(Long id, BigDecimal amount) {
        if (id == null) {
            throw new IllegalArgumentException("Account ID is required for deposit");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be greater than zero");
        }

        String username = getCurrentUsername();

        // Lock row to prevent concurrent lost updates
        Account account = repository.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + id));

        // Enforce ownership: users can deposit only into their own accounts
        if (account.getUser() == null || !account.getUser().getUsername().equals(username)) {
            throw new AccountOwnershipException("Access denied: You do not own account ID " + id);
        }

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentBalance = account.getBalance() != null ? account.getBalance() : BigDecimal.ZERO;
        account.setBalance(currentBalance.add(scaledAmount));

        repository.save(account);

        historyRepo.save(new TransactionHistory(
                account.getId(),
                account.getId(),
                scaledAmount,
                TransactionType.DEPOSIT,
                TransactionStatus.SUCCESS
        ));

        return mapToResponse(account);
    }

    // Transfer Money with Deadlock Prevention, Pessimistic Locking, Idempotency, and Daily Limit
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransactionResponse transferMoney(Long fromId, Long toId, BigDecimal amount, String idempotencyKey) {
        String username = getCurrentUsername();

        // 1. Resolve sender account if fromId is omitted
        if (fromId == null) {
            Account senderAcc = repository.findTopByUsernameOrderByIdDesc(username)
                    .orElseThrow(() -> new ResourceNotFoundException("No active account found for user: " + username));
            fromId = senderAcc.getId();
        }

        // 2. Validate basic input constraints
        if (toId == null) {
            throw new IllegalArgumentException("Recipient account ID is required");
        }
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("Self-transfers are not allowed");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero");
        }

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);

        // 3. Idempotency Check: if this key was already successfully processed, replay the existing result
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            var existingTx = historyRepo.findByIdempotencyKey(idempotencyKey.trim());
            if (existingTx.isPresent()) {
                TransactionHistory tx = existingTx.get();
                return new TransactionResponse(
                        tx.getId(),
                        tx.getSenderId(),
                        tx.getReceiverId(),
                        tx.getAmount(),
                        tx.getCreatedAt(),
                        tx.getType(),
                        tx.getStatus(),
                        tx.getIdempotencyKey()
                );
            }
        }

        // 4. Daily Transfer Limit Aggregation (UTC start of day)
        LocalDateTime startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        BigDecimal totalTransferredToday = historyRepo.sumAmountBySenderIdAndTypeAndStatusAndCreatedAtAfter(
                fromId,
                TransactionType.TRANSFER,
                TransactionStatus.SUCCESS,
                startOfDay
        );

        if (totalTransferredToday.add(scaledAmount).compareTo(dailyTransferLimit) > 0) {
            throw new TransferLimitExceededException(
                    "Daily transfer limit of " + dailyTransferLimit + " exceeded. " +
                    "Already transferred today: " + totalTransferredToday + ", requested: " + scaledAmount
            );
        }

        // 5. Deadlock Prevention: Order row locks by ascending account ID
        Long firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
        Long secondId = fromId.compareTo(toId) < 0 ? toId : fromId;

        Account first = repository.findByIdWithLock(firstId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + firstId));
        Account second = repository.findByIdWithLock(secondId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + secondId));

        Account sender = fromId.equals(first.getId()) ? first : second;
        Account receiver = toId.equals(second.getId()) ? second : first;

        // 6. Verify sender account ownership
        if (sender.getUser() == null || !sender.getUser().getUsername().equals(username)) {
            throw new AccountOwnershipException("Access denied: You do not own sender account ID " + fromId);
        }

        // 7. Check balance
        BigDecimal senderBalance = sender.getBalance() != null ? sender.getBalance() : BigDecimal.ZERO;
        if (senderBalance.compareTo(scaledAmount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance in account " + fromId + ". Available: " + senderBalance);
        }

        BigDecimal receiverBalance = receiver.getBalance() != null ? receiver.getBalance() : BigDecimal.ZERO;

        // 8. Execute balance updates
        sender.setBalance(senderBalance.subtract(scaledAmount));
        receiver.setBalance(receiverBalance.add(scaledAmount));

        repository.save(sender);
        repository.save(receiver);

        // 9. Record single unified ledger entry for the transfer
        TransactionHistory history = new TransactionHistory(
                sender.getId(),
                receiver.getId(),
                scaledAmount,
                TransactionType.TRANSFER,
                TransactionStatus.SUCCESS,
                idempotencyKey != null && !idempotencyKey.trim().isEmpty() ? idempotencyKey.trim() : null
        );
        historyRepo.save(history);

        return new TransactionResponse(
                history.getId(),
                sender.getId(),
                receiver.getId(),
                scaledAmount,
                history.getCreatedAt(),
                TransactionType.TRANSFER_OUT,
                TransactionStatus.SUCCESS,
                history.getIdempotencyKey()
        );
    }

    // Overloaded transferMoney for backward compatibility
    @Transactional(rollbackFor = Exception.class)
    public void transferMoney(Long toId, BigDecimal amount) {
        transferMoney(null, toId, amount, null);
    }

    // Transaction History with Ownership Verification & Single-Entry Mapping
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getHistory(Long accountId, Pageable pageable) {
        String username = getCurrentUsername();

        // Enforce ownership: user can only view history of their own account
        verifyAccountOwnership(accountId, username);

        return historyRepo
                .findBySenderIdOrReceiverId(accountId, accountId, pageable)
                .map(tx -> {
                    TransactionType displayType = tx.getType();
                    if (tx.getType() == TransactionType.TRANSFER) {
                        if (accountId.equals(tx.getSenderId())) {
                            displayType = TransactionType.TRANSFER_OUT;
                        } else if (accountId.equals(tx.getReceiverId())) {
                            displayType = TransactionType.TRANSFER_IN;
                        }
                    }
                    return new TransactionResponse(
                            tx.getId(),
                            tx.getSenderId(),
                            tx.getReceiverId(),
                            tx.getAmount(),
                            tx.getCreatedAt(),
                            displayType,
                            tx.getStatus(),
                            tx.getIdempotencyKey()
                    );
                });
    }

    // Withdraw Money
    @Transactional(rollbackFor = Exception.class)
    public AccountResponse withdraw(Long accountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdraw amount must be greater than zero");
        }

        String username = getCurrentUsername();
        Account account;

        if (accountId != null) {
            account = repository.findByIdWithLock(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
            if (account.getUser() == null || !account.getUser().getUsername().equals(username)) {
                throw new AccountOwnershipException("Access denied: You do not own account ID " + accountId);
            }
        } else {
            Account defaultAccount = repository.findTopByUsernameOrderByIdDesc(username)
                    .orElseThrow(() -> new ResourceNotFoundException("No account found for user: " + username));
            Long defaultId = defaultAccount.getId();
            account = repository.findByIdWithLock(defaultId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + defaultId));
        }

        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentBalance = account.getBalance() != null ? account.getBalance() : BigDecimal.ZERO;

        if (currentBalance.compareTo(scaledAmount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance. Available: " + currentBalance + ", Requested: " + scaledAmount);
        }

        account.setBalance(currentBalance.subtract(scaledAmount));
        repository.save(account);

        historyRepo.save(new TransactionHistory(
                account.getId(),
                account.getId(),
                scaledAmount,
                TransactionType.WITHDRAW,
                TransactionStatus.SUCCESS
        ));

        return mapToResponse(account);
    }

    // Overloaded withdraw for backward compatibility
    @Transactional(rollbackFor = Exception.class)
    public AccountResponse withdraw(BigDecimal amount) {
        return withdraw(null, amount);
    }

    // Get My Primary Account
    @Transactional(readOnly = true)
    public AccountResponse getMyAccount() {
        String username = getCurrentUsername();

        Account account = repository
                .findTopByUsernameOrderByIdDesc(username)
                .orElseThrow(() -> new ResourceNotFoundException("No account found for user: " + username));

        return mapToResponse(account);
    }

    // Get All Accounts for Current User
    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts() {
        String username = getCurrentUsername();
        return repository.findByUserUsername(username).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // Convert Entity -> DTO
    private AccountResponse mapToResponse(Account acc) {
        return new AccountResponse(
                acc.getId(),
                acc.getUsername(),
                acc.getBalance()
        );
    }
}
