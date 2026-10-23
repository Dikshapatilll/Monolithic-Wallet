package com.wallet.wallet.dto;

public class AuthResponse {

    private String message;
    private String token;
    private String username;

    public AuthResponse() {}

    public AuthResponse(String message, String token, String username) {
        this.message = message;
        this.token = token;
        this.username = username;
    }

    public static AuthResponse ofMessage(String message) {
        return new AuthResponse(message, null, null);
    }

    public static AuthResponse ofToken(String token, String username) {
        return new AuthResponse("Authentication successful", token, username);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
