package com.wallet.wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.wallet.wallet.entity.TransactionStatus;
import com.wallet.wallet.entity.TransactionType;

public class TransactionResponse {

    private Long id;
    private Long senderId;
    private Long receiverId;
    private BigDecimal amount;
    private LocalDateTime createdAt;
    private TransactionType type;
    private TransactionStatus status;
    private String idempotencyKey;

    public TransactionResponse() {}

    public TransactionResponse(Long senderId, Long receiverId, BigDecimal amount, LocalDateTime createdAt, TransactionType type, TransactionStatus status) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.amount = amount;
        this.createdAt = createdAt;
        this.type = type;
        this.status = status;
    }

    public TransactionResponse(Long id, Long senderId, Long receiverId, BigDecimal amount, LocalDateTime createdAt, TransactionType type, TransactionStatus status, String idempotencyKey) {
        this.id = id;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.amount = amount;
        this.createdAt = createdAt;
        this.type = type;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
