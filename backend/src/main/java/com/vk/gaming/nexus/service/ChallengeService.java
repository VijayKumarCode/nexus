package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.ChallengeMessage;
import com.vk.gaming.nexus.dto.PlayerStatus;
import com.vk.gaming.nexus.entity.ChallengeEntity;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.enums.ChallengeStatus;
import com.vk.gaming.nexus.enums.MessageType;
import com.vk.gaming.nexus.enums.UserStatus;
import com.vk.gaming.nexus.repository.ChallengeRepository;
import com.vk.gaming.nexus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    @Value("${challenge.expiry-seconds:30}")
    private long challengeExpirySeconds;

    @Value("${challenge.expiry-check-rate-ms:5000}")
    private long challengeExpiryCheckRateMs;

    @Transactional
    public void createChallenge(ChallengeMessage message) {
        // FIX NEXUS-007: Only delete challenges where sender matches (ownership check)
        challengeRepository.findTopByRoomIdOrderByCreatedAtDesc(message.getRoomId()).ifPresent(existing -> {
            if (existing.getSender().equals(message.getSender())) {
                challengeRepository.deleteByRoomId(message.getRoomId());
                log.info("Deleted existing challenge for room {} by sender {}",
                        message.getRoomId(), message.getSender());
            } else {
                log.warn("User {} attempted to overwrite challenge owned by {} in room {}",
                        message.getSender(), existing.getSender(), message.getRoomId());
            }
        });

        // FIX NEXUS-007: Use UUID-based roomId if not provided to prevent guessing
        if (message.getRoomId() == null || message.getRoomId().isBlank()) {
            message.setRoomId(message.getSender() + "_" + message.getReceiver() + "_" + UUID.randomUUID().toString().substring(0, 8));
        }

        LocalDateTime now = LocalDateTime.now();

        ChallengeEntity entity = ChallengeEntity.builder()
                .sender(message.getSender())
                .receiver(message.getReceiver())
                .roomId(message.getRoomId())
                .status(ChallengeStatus.PENDING)
                .expiresAt(now.plusSeconds(challengeExpirySeconds))
                .build();

        challengeRepository.save(entity);

        updatePlayerStatus(message.getSender(), UserStatus.ONLINE);
    }

    @Transactional
    public void respondToChallenge(ChallengeMessage message) {
        if (message.getStatus() == ChallengeStatus.ACCEPTED) {
            acceptChallenge(message.getRoomId());
        } else if (message.getStatus() == ChallengeStatus.REJECTED) {
            challengeRepository.findTopByRoomIdOrderByCreatedAtDesc(message.getRoomId()).ifPresent(c -> {
                c.setStatus(ChallengeStatus.REJECTED);
                challengeRepository.save(c);
            });
        }
    }

    @Transactional
    public void acceptChallenge(String roomId) {
        challengeRepository.findTopByRoomIdOrderByCreatedAtDesc(roomId).ifPresent(challenge -> {

            challenge.setStatus(ChallengeStatus.ACCEPTED);
            challengeRepository.save(challenge);

            updatePlayerStatus(challenge.getSender(), UserStatus.IN_GAME);
            updatePlayerStatus(challenge.getReceiver(), UserStatus.IN_GAME);

            challengeRepository.cancelOtherPending(
                    challenge.getSender(), roomId,
                    ChallengeStatus.CANCELLED, ChallengeStatus.PENDING);

            challengeRepository.cancelOtherPending(
                    challenge.getReceiver(), roomId,
                    ChallengeStatus.CANCELLED, ChallengeStatus.PENDING);

            messagingTemplate.convertAndSend("/topic/lobby.status",
                    new PlayerStatus(challenge.getSender(), UserStatus.IN_GAME));

            messagingTemplate.convertAndSend("/topic/lobby.status",
                    new PlayerStatus(challenge.getReceiver(), UserStatus.IN_GAME));

            log.info("Game started in room {}", roomId);
        });
    }

    @Transactional
    public void cancelStaleChallenges(String disconnectedUser) {
        log.debug("Processing disconnect cleanup for challenges involving user: {}", disconnectedUser);

        List<ChallengeEntity> activeChallenges = challengeRepository
                .findPendingByUser(disconnectedUser, ChallengeStatus.PENDING);

        if (activeChallenges.isEmpty()) {
            return;
        }

        for (ChallengeEntity challenge : activeChallenges) {
            challenge.setStatus(ChallengeStatus.CANCELLED);
            challengeRepository.save(challenge);

            String survivingPlayer = challenge.getSender().equals(disconnectedUser)
                    ? challenge.getReceiver()
                    : challenge.getSender();

            ChallengeMessage abortMsg = new ChallengeMessage();
            abortMsg.setType(MessageType.CHALLENGE_RESPONSE);
            abortMsg.setStatus(ChallengeStatus.CANCELLED);
            abortMsg.setSender(disconnectedUser);
            abortMsg.setReceiver(survivingPlayer);
            abortMsg.setRoomId(challenge.getRoomId());

            log.info("Notifying surviving player [{}] that challenge in room [{}] was cancelled due to disconnect.",
                    survivingPlayer, challenge.getRoomId());

            messagingTemplate.convertAndSend("/topic/challenges/" + survivingPlayer, abortMsg);
        }
    }

    // FIX NEXUS-019: Use single atomic update query, remove separate read-then-update
    @Scheduled(fixedRateString = "${challenge.expiry-check-rate-ms:5000}")
    @Transactional
    public void expireOldChallenges() {
        LocalDateTime now = LocalDateTime.now();

        // Single atomic update — no separate query needed
        int updated = challengeRepository.cancelExpiredChallenges(now, ChallengeStatus.CANCELLED, ChallengeStatus.PENDING);

        if (updated > 0) {
            log.info("Auto-cancelled {} expired challenges", updated);
        }
    }

    private void updatePlayerStatus(String username, UserStatus status) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(status);
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }
}
