package com.wallet.wallet;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.wallet.dto.CreateAccountRequest;
import com.wallet.wallet.dto.DepositRequest;
import com.wallet.wallet.dto.TransferRequest;
import com.wallet.wallet.dto.WithdrawRequest;
import com.wallet.wallet.entity.Account;
import com.wallet.wallet.entity.AppUser;
import com.wallet.wallet.repository.AccountRepository;
import com.wallet.wallet.repository.TransactionHistoryRepository;
import com.wallet.wallet.repository.UserRepository;
import com.wallet.wallet.security.JwtUtil;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionHistoryRepository historyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    private AppUser userAlice;
    private AppUser userBob;
    private Account accountAlice;
    private Account accountBob;
    private String tokenAlice;

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        userAlice = userRepository.save(new AppUser("alice", passwordEncoder.encode("password123")));
        userBob = userRepository.save(new AppUser("bob", passwordEncoder.encode("password123")));

        accountAlice = accountRepository.save(new Account("alice", new BigDecimal("500.00"), userAlice));
        accountBob = accountRepository.save(new Account("bob", new BigDecimal("200.00"), userBob));

        tokenAlice = jwtUtil.generateToken("alice");
    }

    @Test
    @DisplayName("POST /api/accounts/create - Successfully create new account")
    void testCreateAccount() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest(new BigDecimal("150.00"));

        mockMvc.perform(post("/api/accounts/create")
                .header("Authorization", "Bearer " + tokenAlice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    @DisplayName("POST /api/accounts/deposit - Deposit money into owned account")
    void testDepositEndpoint() throws Exception {
        DepositRequest req = new DepositRequest(accountAlice.getId(), new BigDecimal("100.00"));

        mockMvc.perform(post("/api/accounts/deposit")
                .header("Authorization", "Bearer " + tokenAlice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(600.00));
    }

    @Test
    @DisplayName("POST /api/accounts/withdraw - Withdraw money from owned account")
    void testWithdrawEndpoint() throws Exception {
        WithdrawRequest req = new WithdrawRequest(accountAlice.getId(), new BigDecimal("100.00"));

        mockMvc.perform(post("/api/accounts/withdraw")
                .header("Authorization", "Bearer " + tokenAlice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(400.00));
    }

    @Test
    @DisplayName("POST /api/accounts/transfer - Transfer money between accounts")
    void testTransferEndpoint() throws Exception {
        TransferRequest req = new TransferRequest(
                accountAlice.getId(),
                accountBob.getId(),
                new BigDecimal("100.00"),
                "rest-key-001"
        );

        mockMvc.perform(post("/api/accounts/transfer")
                .header("Authorization", "Bearer " + tokenAlice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderId").value(accountAlice.getId()))
                .andExpect(jsonPath("$.receiverId").value(accountBob.getId()))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /api/accounts/{id}/transactions - Paginated history with sorting")
    void testGetTransactionsPagination() throws Exception {
        // Generate transactions
        TransferRequest req = new TransferRequest(
                accountAlice.getId(),
                accountBob.getId(),
                new BigDecimal("25.00"),
                "rest-key-002"
        );

        mockMvc.perform(post("/api/accounts/transfer")
                .header("Authorization", "Bearer " + tokenAlice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/accounts/" + accountAlice.getId() + "/transactions?page=0&size=5")
                .header("Authorization", "Bearer " + tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER_OUT"));
    }

    @Test
    @DisplayName("GET /api/accounts - Get all accounts for authenticated user")
    void testGetAllMyAccounts() throws Exception {
        mockMvc.perform(get("/api/accounts")
                .header("Authorization", "Bearer " + tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }
}
