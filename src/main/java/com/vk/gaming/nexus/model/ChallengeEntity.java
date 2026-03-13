/**
 * Problem No. #141
 * Difficulty: Medium
 * Description: Corrected ChallengeEntity with proper Lombok Builder
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1)
 */
package com.vk.gaming.nexus.model;

import com.vk.gaming.nexus.dto.ChallengeMessage;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "challenges")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor // Required for @Builder to function
public class ChallengeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sender;
    private String receiver;
    private String roomId;

    @Enumerated(EnumType.STRING)
    private ChallengeMessage.ChallengeStatus status;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}