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
import com.wallet.wallet.dto.DepositRequest;
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
class SecurityAndAuthorizationTest {

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
    private String tokenBob;

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        userAlice = userRepository.save(new AppUser("alice", passwordEncoder.encode("password123")));
        userBob = userRepository.save(new AppUser("bob", passwordEncoder.encode("password123")));

        accountAlice = accountRepository.save(new Account("alice", new BigDecimal("500.00"), userAlice));
        accountBob = accountRepository.save(new Account("bob", new BigDecimal("300.00"), userBob));

        tokenAlice = jwtUtil.generateToken("alice");
        tokenBob = jwtUtil.generateToken("bob");
    }

    @Test
    @DisplayName("GET /api/accounts/me without token returns 401 Unauthorized")
    void testUnauthenticatedAccessRejected() throws Exception {
        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/accounts/{id}/transactions with invalid token returns 401 Unauthorized")
    void testInvalidTokenRejected() throws Exception {
        mockMvc.perform(get("/api/accounts/" + accountAlice.getId() + "/transactions")
                .header("Authorization", "Bearer invalid.token.structure"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/accounts/{id}/transactions - Bob accessing Alice's transactions returns 403 Forbidden")
    void testBOLAOwnershipProtectionOnHistory() throws Exception {
        mockMvc.perform(get("/api/accounts/" + accountAlice.getId() + "/transactions")
                .header("Authorization", "Bearer " + tokenBob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied: You do not own account ID " + accountAlice.getId()));
    }

    @Test
    @DisplayName("POST /api/accounts/deposit - Bob depositing into Alice's account returns 403 Forbidden")
    void testBOLAOwnershipProtectionOnDeposit() throws Exception {
        DepositRequest req = new DepositRequest(accountAlice.getId(), new BigDecimal("100.00"));

        mockMvc.perform(post("/api/accounts/deposit")
                .header("Authorization", "Bearer " + tokenBob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("GET /api/accounts/me with valid Alice token returns Alice's account")
    void testAuthorizedAccessSuccess() throws Exception {
        mockMvc.perform(get("/api/accounts/me")
                .header("Authorization", "Bearer " + tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.balance").value(500.00));
    }
}
