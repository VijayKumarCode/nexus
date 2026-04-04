package com.vk.gaming.nexus.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name")
    private String fullName;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    @JsonIgnore             // BUG 7 FIX: never serialize email in lobby/leaderboard
    private String email;

    @Column(nullable = false)
    @JsonIgnore             // BUG 7 FIX: never serialize password hash in any response
    private String password;

    @Column(name = "is_enabled", nullable = false)
    @JsonIgnore             // BUG 7 FIX: internal flag, not needed by frontend
    private boolean enabled = false;   // BUG 10 FIX: renamed from isEnabled → enabled
    // Lombok now generates isEnabled()/setEnabled() cleanly

    @JsonIgnore             // BUG 7 FIX: activation token must never leave the server
    @Column(unique = true)
    private String activationToken;

    @Column(name = "last_seen")
    @JsonIgnore             // internal presence tracking, not needed by frontend
    private Long lastSeen;

    @Column(nullable = false)
    private Integer wins = 0;

    @Column(nullable = false)
    private Integer losses = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.OFFLINE;

    // BUG 9 FIX: removed isOnline field entirely — it duplicated `status` and
    // was never updated. Only `status` is used throughout UserService.

    public enum UserStatus {
        ONLINE,
        IDLE,
        IN_GAME,
        OFFLINE
    }
}