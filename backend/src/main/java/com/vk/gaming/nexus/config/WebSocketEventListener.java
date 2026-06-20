package com.vk.gaming.nexus.config;

import com.vk.gaming.nexus.dto.PlayerStatus;
import com.vk.gaming.nexus.enums.UserStatus;
import com.vk.gaming.nexus.service.ChallengeService;
import com.vk.gaming.nexus.service.GameService;
import com.vk.gaming.nexus.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private final UserService userService;
    private final ChallengeService challengeService;
    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.vk.gaming.nexus.repository.UserRepository userRepository;
    private final com.vk.gaming.nexus.repository.ChallengeRepository challengeRepository;

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();

        if (user == null) {
            log.warn("WebSocket CONNECT attempted without valid JWT Authentication principal");
            return;
        }

        String username = user.getName();

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put("username", username);
            log.info("WebSocket session stored authenticated username={}", username);
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            log.debug("WebSocket disconnect — no session attributes");
            return;
        }
        Object val = attrs.get("username");
        if (val == null) {
            log.debug("WebSocket disconnect — username not present in session attributes");
            return;
        }
        String username = val.toString();
        log.info("User disconnected: {}", username);
        try {
            userRepository.findByUsername(username).ifPresent(u -> {
                u.setStatus(UserStatus.OFFLINE);
                u.setLastSeen(System.currentTimeMillis());
                userRepository.save(u);
            });
            challengeRepository.cancelAllPendingForUser(username, com.vk.gaming.nexus.enums.ChallengeStatus.CANCELLED, com.vk.gaming.nexus.enums.ChallengeStatus.PENDING);
            gameService.handlePlayerDisconnect(username);
            messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(username, UserStatus.OFFLINE));
        } catch (Exception e) {
            log.warn("Error during disconnect cleanup for {}: {}", username, e.getMessage());
        }
    }
}
