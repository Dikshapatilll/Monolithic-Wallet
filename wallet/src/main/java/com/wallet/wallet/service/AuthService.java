package com.wallet.wallet.service;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wallet.wallet.dto.AuthRequest;
import com.wallet.wallet.dto.AuthResponse;
import com.wallet.wallet.entity.AppUser;
import com.wallet.wallet.exception.DuplicateRequestException;
import com.wallet.wallet.repository.UserRepository;
import com.wallet.wallet.security.JwtUtil;

@Service
public class AuthService {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepo, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    // Register
    @Transactional
    public AuthResponse register(AuthRequest req) {
        String username = req.getUsername().trim();

        if (userRepo.existsByUsername(username)) {
            throw new DuplicateRequestException("Username '" + username + "' is already taken");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.getPassword()));

        userRepo.save(user);

        return AuthResponse.ofMessage("User registered successfully");
    }

    // Login
    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest req) {
        String username = req.getUsername().trim();

        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        String token = jwtUtil.generateToken(user.getUsername());
        return AuthResponse.ofToken(token, user.getUsername());
    }
}
