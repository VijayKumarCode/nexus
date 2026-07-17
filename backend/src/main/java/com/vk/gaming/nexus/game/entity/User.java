package com.vk.gaming.nexus.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vk.gaming.nexus.game.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
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
    @JsonIgnore
    private String email;

    @Column(nullable = false)
    @JsonIgnore
    private String password;

    @Column(name = "is_enabled", nullable = false)
    @JsonIgnore
    private boolean enabled = false;

    @JsonIgnore
    @Column(name = "activationtoken", unique = true)
    private String activationToken;
    
    @Column(name = "last_seen")
    @JsonIgnore
    private Long lastSeen;

    @Column(nullable = false)
    private Integer wins = 0;

    @Column(nullable = false)
    private Integer losses = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.OFFLINE;
}
