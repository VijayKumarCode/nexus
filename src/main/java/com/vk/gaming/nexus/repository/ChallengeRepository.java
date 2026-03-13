package com.vk.gaming.nexus.repository;


import com.vk.gaming.nexus.model.ChallengeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChallengeRepository extends JpaRepository<ChallengeEntity, Long> {
    Optional<ChallengeEntity> findByRoomId(String roomId);

    @Modifying
    @Query("UPDATE ChallengeEntity c SET c.status = 'CANCELLED' " +
            "WHERE (c.sender = :username OR c.receiver = :username) " +
            "AND c.status = 'PENDING' AND c.roomId <> :activeRoomId")
    void cancelAllOtherPendingChallenges(String username, String activeRoomId);
}
