package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.*;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.model.ChallengeEntity;
import com.vk.gaming.nexus.repository.ChallengeRepository;
import com.vk.gaming.nexus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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


    // ========================= CREATE =========================
    @Transactional
    public void createChallenge(ChallengeMessage message) {

        challengeRepository.deleteByRoomId(message.getRoomId());

        LocalDateTime now = LocalDateTime.now();

        ChallengeEntity entity = ChallengeEntity.builder()
                .sender(message.getSender())
                .receiver(message.getReceiver())
                .roomId(message.getRoomId())
                .status(ChallengeStatus.PENDING)
                .expiresAt(now.plusSeconds(30)) // 🔥 CLEAN TTL
                .build();

        challengeRepository.save(entity);

        updatePlayerStatus(message.getSender(), User.UserStatus.ONLINE);
    }

    // ========================= RESPOND =========================
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

    // ========================= ACCEPT =========================
    @Transactional
    public void acceptChallenge(String roomId) {

        challengeRepository.findTopByRoomIdOrderByCreatedAtDesc(roomId).ifPresent(challenge -> {

            challenge.setStatus(ChallengeStatus.ACCEPTED);
            challengeRepository.save(challenge);

            updatePlayerStatus(challenge.getSender(), User.UserStatus.IN_GAME);
            updatePlayerStatus(challenge.getReceiver(), User.UserStatus.IN_GAME);

            // Cancel other pending challenges
            challengeRepository.cancelOtherPending(
                    challenge.getSender(), roomId,
                    ChallengeStatus.CANCELLED, ChallengeStatus.PENDING);

            challengeRepository.cancelOtherPending(
                    challenge.getReceiver(), roomId,
                    ChallengeStatus.CANCELLED, ChallengeStatus.PENDING);

            messagingTemplate.convertAndSend("/topic/lobby.status",
                    new PlayerStatus(challenge.getSender(), "IN_GAME"));

            messagingTemplate.convertAndSend("/topic/lobby.status",
                    new PlayerStatus(challenge.getReceiver(), "IN_GAME"));

            log.info("Game started in room {}", roomId);
        });
    }

    // ========================= DISCONNECT CLEANUP =========================
    @Transactional
    public void cancelStaleChallenges(String username) {

        List<ChallengeEntity> pending =
                challengeRepository.findPendingByUser(
                        username,
                        ChallengeStatus.PENDING
                );

        // 🔥 Bulk update (FAST)
        challengeRepository.cancelAllPendingForUser(
                username,
                ChallengeStatus.CANCELLED,
                ChallengeStatus.PENDING
        );

        // 🔥 Notify affected users
        for (ChallengeEntity stale : pending) {
            notifyCancellation(username, stale);
        }

        log.info("Cancelled {} stale challenges for {}", pending.size(), username);
    }

    // ========================= AUTO EXPIRY (🔥 NEW FEATURE) =========================
    @Scheduled(fixedRate = 5000)
    @Transactional
    public void expireOldChallenges() {

        LocalDateTime now = LocalDateTime.now();

        // Fetch only expired
        List<ChallengeEntity> expired =
                challengeRepository.findByStatusAndExpiresAtBefore(
                        ChallengeStatus.PENDING,
                        now
                );

        // Bulk cancel
        int updated = challengeRepository.cancelExpiredChallenges(
                now,
                ChallengeStatus.CANCELLED,
                ChallengeStatus.PENDING
        );

        // Notify users
        for (ChallengeEntity c : expired) {
            notifyCancellation("SYSTEM", c);
        }

        if (updated > 0) {
            log.info("Auto-cancelled {} expired challenges", updated);
        }
    }

    // ========================= NOTIFY =========================
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

    // ========================= STATUS =========================
    private void updatePlayerStatus(String username, User.UserStatus status) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(status);
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }
}