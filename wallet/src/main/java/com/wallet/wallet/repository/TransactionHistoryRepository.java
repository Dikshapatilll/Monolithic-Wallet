package com.wallet.wallet.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.wallet.wallet.entity.TransactionHistory;
import com.wallet.wallet.entity.TransactionStatus;
import com.wallet.wallet.entity.TransactionType;

@Repository
public interface TransactionHistoryRepository extends JpaRepository<TransactionHistory, Long> {

    Page<TransactionHistory> findBySenderIdOrReceiverId(Long senderId, Long receiverId, Pageable pageable);

    Optional<TransactionHistory> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionHistory t " +
           "WHERE t.senderId = :senderId " +
           "AND t.type = :type " +
           "AND t.status = :status " +
           "AND t.createdAt >= :since")
    BigDecimal sumAmountBySenderIdAndTypeAndStatusAndCreatedAtAfter(
        @Param("senderId") Long senderId,
        @Param("type") TransactionType type,
        @Param("status") TransactionStatus status,
        @Param("since") LocalDateTime since
    );
}
