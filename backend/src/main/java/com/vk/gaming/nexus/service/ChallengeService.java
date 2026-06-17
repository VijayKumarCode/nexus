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
        challengeRepository.deleteByRoomId(message.getRoomId());

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

    /**
     * Cleans up any pending challenges involving a disconnected user
     * and sends a cancellation broadcast to the surviving opponent.
     *
     * @param disconnectedUser The username of the player who went offline.
     */
    @Transactional
    public void cancelStaleChallenges(String disconnectedUser) {
        log.debug("Processing disconnect cleanup for challenges involving user: {}", disconnectedUser);

        // 1. Leverage your existing custom repository query
        List<ChallengeEntity> activeChallenges = challengeRepository
                .findPendingByUser(disconnectedUser, ChallengeStatus.PENDING);

        if (activeChallenges.isEmpty()) {
            return;
        }

        for (ChallengeEntity challenge : activeChallenges) {
            // 2. Terminate the challenge state
            challenge.setStatus(ChallengeStatus.CANCELLED);
            challengeRepository.save(challenge);

            // 3. Extract the active player who is still online waiting in the modal
            String survivingPlayer = challenge.getSender().equals(disconnectedUser)
                    ? challenge.getReceiver()
                    : challenge.getSender();

            // 4. Construct the payload matching your ChallengeMessage DTO structure
            ChallengeMessage abortMsg = new ChallengeMessage();
            abortMsg.setType(MessageType.CHALLENGE_RESPONSE);
            abortMsg.setStatus(ChallengeStatus.CANCELLED);
            abortMsg.setSender(disconnectedUser);
            abortMsg.setReceiver(survivingPlayer);
            abortMsg.setRoomId(challenge.getRoomId());

            log.info("Notifying surviving player [{}] that challenge in room [{}] was cancelled due to disconnect.",
                    survivingPlayer, challenge.getRoomId());

            // 5. Publish to the exact topic your frontend is subscribing to in nexus.js
            messagingTemplate.convertAndSend("/topic/challenges/" + survivingPlayer, abortMsg);
        }
    }

    @Scheduled(fixedRateString = "${challenge.expiry-check-rate-ms:5000}")
    @Transactional
    public void expireOldChallenges() {
        LocalDateTime now = LocalDateTime.now();

        List<ChallengeEntity> expired =
                challengeRepository.findByStatusAndExpiresAtBefore(ChallengeStatus.PENDING, now);

        int updated = challengeRepository.cancelExpiredChallenges(now, ChallengeStatus.CANCELLED, ChallengeStatus.PENDING);

        for (ChallengeEntity c : expired) {
            notifyCancellation("SYSTEM", c);
        }

        if (updated > 0) {
            log.info("Auto-cancelled {} expired challenges", updated);
        }
    }

    private void notifyCancellation(String requester, ChallengeEntity stale) {
        ChallengeMessage msg = new ChallengeMessage();
        msg.setType(MessageType.CHALLENGE_RESPONSE);
        msg.setStatus(ChallengeStatus.CANCELLED);
        msg.setSender(requester);
        msg.setRoomId(stale.getRoomId());

        String target = stale.getSender().equals(requester)
                ? stale.getReceiver()
                : stale.getSender();

        msg.setReceiver(target);

        messagingTemplate.convertAndSend("/topic/challenges/" + target, msg);
    }

    private void updatePlayerStatus(String username, UserStatus status) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(status);
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }
}
