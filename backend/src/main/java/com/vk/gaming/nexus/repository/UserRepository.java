package com.vk.gaming.nexus.repository;

import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.enums.UserStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByActivationToken(String token);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<User> findByStatus(UserStatus status);

    @Query("SELECT u FROM User u WHERE (u.wins + u.losses) > 0 ORDER BY u.wins DESC LIMIT 10")
    List<User> findTop10ActivePlayers();

    @Modifying
    @Transactional
    @Query("""
        UPDATE User u
        SET u.status = :offline
        WHERE u.lastSeen < :cutoff
        AND u.status <> :offline
    """)
    int markInactiveUsersOffline(
            @Param("cutoff") long cutoff,
            @Param("offline") UserStatus offline
    );

    @Modifying
    @Transactional
    @Query("""
        UPDATE User u
        SET u.lastSeen = :time,
            u.status   = CASE
                WHEN u.status <> :inGame THEN :online
                ELSE u.status
            END
        WHERE u.username = :username
    """)
    int updateHeartbeat(
            @Param("username") String username,
            @Param("time")     long time,
            @Param("inGame")   UserStatus inGame,
            @Param("online")   UserStatus online
    );

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.wins = u.wins + 1 WHERE u.username = :username")
    int incrementWins(@Param("username") String username);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.losses = u.losses + 1 WHERE u.username = :username")
    int incrementLosses(@Param("username") String username);
}
