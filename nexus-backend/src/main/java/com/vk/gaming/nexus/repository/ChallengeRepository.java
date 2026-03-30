package com.vk.gaming.nexus.repository;

import com.vk.gaming.nexus.dto.ChallengeStatus;
import com.vk.gaming.nexus.model.ChallengeEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<ChallengeEntity, Long> {

    // ========================= BASIC =========================
    // ✅ Gets most recent one, ignores stale duplicates
    Optional<ChallengeEntity> findTopByRoomIdOrderByCreatedAtDesc(String roomId);

    void deleteByRoomId(String roomId);

    // ========================= FIND =========================
    @Query("""
        SELECT c FROM ChallengeEntity c
        WHERE (c.sender = :username OR c.receiver = :username)
        AND c.status = :status
    """)
    List<ChallengeEntity> findPendingByUser(@Param("username") String username,
                                            @Param("status") ChallengeStatus status);

    // ========================= BULK CANCEL USER =========================
    @Modifying
    @Query("""
        UPDATE ChallengeEntity c
        SET c.status = :cancelled
        WHERE (c.sender = :username OR c.receiver = :username)
        AND c.status = :pending
    """)
    int cancelAllPendingForUser(@Param("username") String username,
                                @Param("cancelled") ChallengeStatus cancelled,
                                @Param("pending") ChallengeStatus pending);

    // ========================= AUTO EXPIRY =========================
    @Modifying
    @Query("""
        UPDATE ChallengeEntity c
        SET c.status = :cancelled
        WHERE c.status = :pending
        AND c.expiresAt < :now
    """)
    int cancelExpiredChallenges(@Param("now") LocalDateTime now,
                                @Param("cancelled") ChallengeStatus cancelled,
                                @Param("pending") ChallengeStatus pending);

    // 🔥 CORRECT METHOD (replaces createdAt version)
    List<ChallengeEntity> findByStatusAndExpiresAtBefore(
            ChallengeStatus status,
            LocalDateTime now
    );

    // ========================= CANCEL OTHERS =========================
    @Modifying
    @Query("""
        UPDATE ChallengeEntity c
        SET c.status = :cancelled
        WHERE c.sender = :username
        AND c.roomId != :roomId
        AND c.status = :pending
    """)
    int cancelOtherPending(@Param("username") String username,
                           @Param("roomId") String roomId,
                           @Param("cancelled") ChallengeStatus cancelled,
                           @Param("pending") ChallengeStatus pending);
}