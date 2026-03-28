package com.vk.gaming.nexus.config;

import com.vk.gaming.nexus.service.ChallengeService;
import com.vk.gaming.nexus.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private final UserService userService;
    private final ChallengeService challengeService;

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {

        String username = extractUsername(event); // your existing logic

        if (username == null) return;

        log.info("User disconnected: {}", username);

        // 1. Mark offline
        userService.logoutUser(username);

        // 2. 🔥 Cancel stale challenges
        challengeService.cancelStaleChallenges(username);
    }

    private String extractUsername(SessionDisconnectEvent event) {

        if (event.getMessage() == null) return null;

        var accessor = org.springframework.messaging.simp.stomp.StompHeaderAccessor
                .wrap(event.getMessage());

        if (accessor.getSessionAttributes() == null) return null;

        Object username = accessor.getSessionAttributes().get("username");

        return username != null ? username.toString() : null;
    }
}