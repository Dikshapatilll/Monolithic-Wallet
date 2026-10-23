package com.wallet.wallet_auth_service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wallet.wallet_auth_service.dto.AuthRequest;
import com.wallet.wallet_auth_service.security.JwtUtil;
import com.wallet.wallet_auth_service.service.AuthService;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService service;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService service, JwtUtil jwtUtil) {
        this.service = service;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public String register(@RequestBody AuthRequest req) {
        return service.register(req);
    }

    @PostMapping("/login")
    public String login(@RequestBody AuthRequest req) {
        return service.login(req);
    }

    // IMPORTANT: used by wallet-account-service
    @GetMapping("/validate")
    public String validate(@RequestParam String token) {
        if(!jwtUtil.validateToken(token)) {
            throw new RuntimeException("Invalid token");
        }
        return jwtUtil.extractUsername(token);
    }
}
