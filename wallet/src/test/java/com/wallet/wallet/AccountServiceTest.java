package com.wallet.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.wallet.wallet.dto.AccountResponse;
import com.wallet.wallet.dto.TransactionResponse;
import com.wallet.wallet.entity.Account;
import com.wallet.wallet.entity.AppUser;
import com.wallet.wallet.entity.TransactionStatus;
import com.wallet.wallet.entity.TransactionType;
import com.wallet.wallet.exception.AccountOwnershipException;
import com.wallet.wallet.exception.InsufficientBalanceException;
import com.wallet.wallet.exception.ResourceNotFoundException;
import com.wallet.wallet.exception.TransferLimitExceededException;
import com.wallet.wallet.repository.AccountRepository;
import com.wallet.wallet.repository.TransactionHistoryRepository;
import com.wallet.wallet.repository.UserRepository;
import com.wallet.wallet.service.AccountService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionHistoryRepository historyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser userAlice;
    private AppUser userBob;
    private Account accountAlice;
    private Account accountBob;

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        userAlice = userRepository.save(new AppUser("alice", passwordEncoder.encode("password123")));
        userBob = userRepository.save(new AppUser("bob", passwordEncoder.encode("password123")));

        accountAlice = accountRepository.save(new Account("alice", new BigDecimal("1000.00"), userAlice));
        accountBob = accountRepository.save(new Account("bob", new BigDecimal("500.00"), userBob));

        // Default authentication context as Alice
        setAuth("alice");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuth(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("Deposit - Valid deposit updates balance and creates DEPOSIT history")
    void testDepositSuccess() {
        AccountResponse res = accountService.deposit(accountAlice.getId(), new BigDecimal("250.50"));

        assertEquals(new BigDecimal("1250.50"), res.getBalance());

        Account updated = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("1250.50"), updated.getBalance());

        var history = historyRepository.findAll();
        assertEquals(1, history.size());
        assertEquals(TransactionType.DEPOSIT, history.get(0).getType());
        assertEquals(new BigDecimal("250.50"), history.get(0).getAmount());
    }

    @Test
    @DisplayName("Deposit - Negative amount throws IllegalArgumentException and balance unchanged")
    void testDepositNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                accountService.deposit(accountAlice.getId(), new BigDecimal("-50.00"))
        );

        Account unchanged = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("1000.00"), unchanged.getBalance());
        assertEquals(0, historyRepository.count());
    }

    @Test
    @DisplayName("Withdraw - Valid withdrawal decreases balance and creates WITHDRAW history")
    void testWithdrawSuccess() {
        AccountResponse res = accountService.withdraw(accountAlice.getId(), new BigDecimal("300.00"));

        assertEquals(new BigDecimal("700.00"), res.getBalance());

        Account updated = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("700.00"), updated.getBalance());

        var history = historyRepository.findAll();
        assertEquals(1, history.size());
        assertEquals(TransactionType.WITHDRAW, history.get(0).getType());
    }

    @Test
    @DisplayName("Withdraw - Insufficient balance throws InsufficientBalanceException and rolls back")
    void testWithdrawInsufficientBalance() {
        assertThrows(InsufficientBalanceException.class, () ->
                accountService.withdraw(accountAlice.getId(), new BigDecimal("1500.00"))
        );

        Account unchanged = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("1000.00"), unchanged.getBalance());
        assertEquals(0, historyRepository.count());
    }

    @Test
    @DisplayName("Transfer - Successful transfer updates sender and receiver balances, single ledger entry")
    void testTransferSuccess() {
        TransactionResponse tx = accountService.transferMoney(
                accountAlice.getId(),
                accountBob.getId(),
                new BigDecimal("200.00"),
                "tx-key-100"
        );

        assertEquals(TransactionStatus.SUCCESS, tx.getStatus());

        Account sender = accountRepository.findById(accountAlice.getId()).orElseThrow();
        Account receiver = accountRepository.findById(accountBob.getId()).orElseThrow();

        assertEquals(new BigDecimal("800.00"), sender.getBalance());
        assertEquals(new BigDecimal("700.00"), receiver.getBalance());

        // Verify only 1 record is created in the database
        assertEquals(1, historyRepository.count());

        // Verify Alice sees TRANSFER_OUT
        var aliceHistory = accountService.getHistory(accountAlice.getId(), PageRequest.of(0, 10));
        assertEquals(1, aliceHistory.getContent().size());
        assertEquals(TransactionType.TRANSFER_OUT, aliceHistory.getContent().get(0).getType());

        // Verify Bob sees TRANSFER_IN
        setAuth("bob");
        var bobHistory = accountService.getHistory(accountBob.getId(), PageRequest.of(0, 10));
        assertEquals(1, bobHistory.getContent().size());
        assertEquals(TransactionType.TRANSFER_IN, bobHistory.getContent().get(0).getType());
    }

    @Test
    @DisplayName("Transfer - Self-transfer throws IllegalArgumentException")
    void testTransferSelfTransferRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                accountService.transferMoney(accountAlice.getId(), accountAlice.getId(), new BigDecimal("100.00"), null)
        );

        Account unchanged = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("1000.00"), unchanged.getBalance());
    }

    @Test
    @DisplayName("Transfer - Non-existent receiver throws ResourceNotFoundException and sender balance intact")
    void testTransferRecipientNotFound() {
        assertThrows(ResourceNotFoundException.class, () ->
                accountService.transferMoney(accountAlice.getId(), 99999L, new BigDecimal("100.00"), null)
        );

        Account unchanged = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("1000.00"), unchanged.getBalance());
    }

    @Test
    @DisplayName("Transfer - Insufficient balance rolls back entire transaction")
    void testTransferInsufficientBalanceRollback() {
        assertThrows(InsufficientBalanceException.class, () ->
                accountService.transferMoney(accountAlice.getId(), accountBob.getId(), new BigDecimal("2000.00"), null)
        );

        Account sender = accountRepository.findById(accountAlice.getId()).orElseThrow();
        Account receiver = accountRepository.findById(accountBob.getId()).orElseThrow();

        assertEquals(new BigDecimal("1000.00"), sender.getBalance());
        assertEquals(new BigDecimal("500.00"), receiver.getBalance());
        assertEquals(0, historyRepository.count());
    }

    @Test
    @DisplayName("Transfer - Idempotency key replay returns identical result without double-debiting")
    void testTransferIdempotency() {
        String key = "unique-client-key-abc-123";

        TransactionResponse firstResponse = accountService.transferMoney(
                accountAlice.getId(),
                accountBob.getId(),
                new BigDecimal("150.00"),
                key
        );

        // Immediate retry with the exact same idempotency key
        TransactionResponse secondResponse = accountService.transferMoney(
                accountAlice.getId(),
                accountBob.getId(),
                new BigDecimal("150.00"),
                key
        );

        // Transaction ID and amount are identical
        assertEquals(firstResponse.getId(), secondResponse.getId());

        // Balances must be debited ONLY ONCE
        Account sender = accountRepository.findById(accountAlice.getId()).orElseThrow();
        Account receiver = accountRepository.findById(accountBob.getId()).orElseThrow();

        assertEquals(new BigDecimal("850.00"), sender.getBalance());
        assertEquals(new BigDecimal("650.00"), receiver.getBalance());
        assertEquals(1, historyRepository.count());
    }

    @Test
    @DisplayName("Transfer - Daily transfer limit aggregation prevents cumulative overspending")
    void testDailyTransferLimitEnforcement() {
        // Daily limit configured as 10000.00
        accountAlice.setBalance(new BigDecimal("25000.00"));
        accountRepository.save(accountAlice);

        // First transfer: 6000.00
        accountService.transferMoney(accountAlice.getId(), accountBob.getId(), new BigDecimal("6000.00"), "tx-1");

        // Second transfer: 3500.00 (Total today = 9500.00, <= 10000.00)
        accountService.transferMoney(accountAlice.getId(), accountBob.getId(), new BigDecimal("3500.00"), "tx-2");

        // Third transfer: 1000.00 (Total would be 10500.00 > 10000.00) -> must be rejected!
        assertThrows(TransferLimitExceededException.class, () ->
                accountService.transferMoney(accountAlice.getId(), accountBob.getId(), new BigDecimal("1000.00"), "tx-3")
        );

        Account sender = accountRepository.findById(accountAlice.getId()).orElseThrow();
        assertEquals(new BigDecimal("15500.00"), sender.getBalance()); // 25000 - 9500
    }

    @Test
    @DisplayName("Concurrency - Multiple threads transferring simultaneously maintain exact ledger invariants")
    void testConcurrentTransfersIntegrity() throws Exception {
        int threads = 10;
        BigDecimal transferAmount = new BigDecimal("50.00");
        // Alice starts with 1000.00. 10 threads transfer 50.00 each -> Total 500.00 transferred.

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String key = "concurrent-tx-" + i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // ensure all threads start simultaneously
                    setAuth("alice");
                    accountService.transferMoney(accountAlice.getId(), accountBob.getId(), transferAmount, key);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // fire all threads
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        assertTrue(finished, "All concurrent tasks should complete");
        executor.shutdown();

        // Verify futures had no exceptions
        for (Future<?> f : futures) {
            f.get();
        }

        Account sender = accountRepository.findById(accountAlice.getId()).orElseThrow();
        Account receiver = accountRepository.findById(accountBob.getId()).orElseThrow();

        // 1000 - (10 * 50) = 500.00
        assertEquals(new BigDecimal("500.00"), sender.getBalance().setScale(2, RoundingMode.HALF_UP));
        // 500 + (10 * 50) = 1000.00
        assertEquals(new BigDecimal("1000.00"), receiver.getBalance().setScale(2, RoundingMode.HALF_UP));

        // Invariant: Total system balance must equal initial total (1000 + 500 = 1500)
        assertEquals(new BigDecimal("1500.00"), sender.getBalance().add(receiver.getBalance()).setScale(2, RoundingMode.HALF_UP));
    }
}
