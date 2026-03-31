/**
 * Problem No. #142
 * Difficulty: Medium
 * Description: Refactored ChallengeEntity to remove @Data antipattern
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1)
 * Space Complexity: O(1)
 */
/*
 * Problem No. #174
 * Difficulty: Medium
 * Description: Refactored ChallengeEntity to replace @Data with specific Lombok annotations for JPA safety
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1)
 * Space Complexity: O(1)
 */
package com.vk.gaming.nexus.model;

import com.vk.gaming.nexus.dto.ChallengeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "challenges")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sender;
    private String receiver;
    private String roomId;

    @Enumerated(EnumType.STRING)
    private ChallengeStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}