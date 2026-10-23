package com.wallet.wallet_auth_service.service;


import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.wallet.wallet_auth_service.dto.AuthRequest;
import com.wallet.wallet_auth_service.entity.AppUser;
import com.wallet.wallet_auth_service.repository.AppUserRepository;
import com.wallet.wallet_auth_service.security.JwtUtil;

@Service
public class AuthService {
    private final JwtUtil jwtUtil;
    private final AppUserRepository repo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(AppUserRepository repo, JwtUtil jwt) {
        this.repo = repo;
        this.jwtUtil = jwt;
    }

    // Register
    public String register(AuthRequest req) {

        if(repo.findByUsername(req.getUsername()).isPresent()) {
            throw new RuntimeException("User already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(req.getUsername());
        user.setPassword(encoder.encode(req.getPassword()));

        repo.save(user);

        return "User registered!";
    }

    // Login
    public String login(AuthRequest req) {

        AppUser user = repo.findByUsername(req.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if(!encoder.matches(req.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invlaid Password");
        }

        return jwtUtil.generateToken(user.getUsername());
    }
}
