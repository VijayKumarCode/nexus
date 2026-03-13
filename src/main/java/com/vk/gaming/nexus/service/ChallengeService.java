/**
 * Problem No. #144
 * Difficulty: Medium
 * Description: Challenge Service with Persistence logic
 */
package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.ChallengeMessage;
import com.vk.gaming.nexus.model.ChallengeEntity; // Fixed import
import com.vk.gaming.nexus.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChallengeService {
    private final ChallengeRepository challengeRepository;

    @Transactional
    public void createChallenge(ChallengeMessage message) {
        // Build and save the entity to PostgreSQL
        ChallengeEntity entity = ChallengeEntity.builder()
                .sender(message.getSender())
                .receiver(message.getReceiver())
                .roomId(message.getRoomId())
                .status(ChallengeMessage.ChallengeStatus.PENDING)
                .build();

        challengeRepository.save(entity);
    }

    @Transactional
    public void acceptChallenge(ChallengeMessage message) {
        ChallengeEntity challenge = challengeRepository.findByRoomId(message.getRoomId())
                .orElseThrow(() -> new RuntimeException("Challenge not found"));

        challenge.setStatus(ChallengeMessage.ChallengeStatus.ACCEPTED);
        challengeRepository.save(challenge);

        // Cancel all other pending requests for these users
        challengeRepository.cancelAllOtherPendingChallenges(message.getSender(), message.getRoomId());
        challengeRepository.cancelAllOtherPendingChallenges(message.getReceiver(), message.getRoomId());
    }
}