package com.vk.gaming.nexus.config;

import com.vk.gaming.nexus.service.JwtService;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.socket.config.annotation.*;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties appProperties;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public WebSocketConfig(AppProperties appProperties, JwtService jwtService, UserDetailsService userDetailsService) {
        this.appProperties = appProperties;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // ─── FIX 3: CONFIGURE DEDICATED HEARTBEAT TASK SCHEDULER ───
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10000, 10000}) // Ping every 10s, expect pong every 10s
                .setTaskScheduler(heartbeatScheduler());

        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(@Nonnull StompEndpointRegistry registry) {
        List<String> origins = appProperties.getAllowedOrigins();
        String[] allowedPatterns;

        if (origins == null || origins.isEmpty()) {
            log.warn("CRITICAL SECURITY WARNING: 'app.allowed-origins' is empty or unconfigured. Falling back to safe localized sandbox environments.");
            // Protects production from accidental open access while allowing seamless local debugging
            allowedPatterns = new String[]{"http://localhost:8080", "http://127.0.0.1:5500", "http://localhost:3000"};
        } else {
            allowedPatterns = origins.toArray(new String[0]);
        }

        registry.addEndpoint("/game-websocket")
                .setAllowedOriginPatterns(allowedPatterns) // Prevents wildcards (*) while remaining flexible across browser mutations
                .withSockJS(); // Preserves SockJS HTTP frame streaming structures

        log.info("Nexus WebSocket Engine initialized successfully. Stomp connection handshakes bound to origins: {}", Arrays.toString(allowedPatterns));
    }

    // ─── BEAN DEFINITION FOR WEB-SOCKET HEARTBEATS ───
    @Bean
    public ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-thread-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null) {
                    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                        String authHeader = accessor.getFirstNativeHeader("Authorization");
                        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                            log.warn("WebSocket CONNECT: missing or invalid Authorization header");
                            throw new org.springframework.messaging.MessagingException("Missing or invalid Authorization header");
                        }
                        String jwt = authHeader.substring(7);
                        try {
                            String username = jwtService.extractUsername(jwt);
                            if (username != null) {
                                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                                if (userDetails.isEnabled() && jwtService.validateToken(jwt, userDetails.getUsername())) {
                                    UsernamePasswordAuthenticationToken authToken =
                                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                                    accessor.setUser(authToken);
                                    if (accessor.getSessionAttributes() != null) {
                                        accessor.getSessionAttributes().put("username", username);
                                    }
                                    log.debug("WebSocket CONNECT authenticated for user: {}", username);
                                } else {
                                    log.warn("WebSocket CONNECT: token invalid or user disabled: {}", username);
                                    throw new org.springframework.messaging.MessagingException("Invalid token or disabled user");
                                }
                            } else {
                                throw new org.springframework.messaging.MessagingException("Invalid token payload");
                            }
                        } catch (Exception e) {
                            log.warn("WebSocket CONNECT: JWT validation failed: {}", e.getMessage());
                            throw new org.springframework.messaging.MessagingException("Authentication failed: " + e.getMessage());
                        }
                    } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                        Principal principal = accessor.getUser();
                        if (principal == null) {
                            log.warn("WebSocket SUBSCRIBE attempt without authentication");
                            throw new org.springframework.messaging.MessagingException("Unauthorized subscription attempt");
                        }
                        String username = principal.getName();
                        String destination = accessor.getDestination();
                        if (destination != null) {
                            if (destination.startsWith("/topic/challenges/")) {
                                String pathUser = destination.substring("/topic/challenges/".length());
                                if (!username.equals(pathUser)) {
                                    log.warn("User {} attempted unauthorized subscription to {}", username, destination);
                                    throw new org.springframework.messaging.MessagingException("Forbidden: cannot subscribe to other user's challenges");
                                }
                            } else if (destination.startsWith("/topic/game/")) {
                                String roomId = destination.substring("/topic/game/".length());
                                String[] players = roomId.split("_");
                                if (players.length >= 2) {
                                    if (!username.equals(players[0]) && !username.equals(players[1])) {
                                        log.warn("User {} attempted unauthorized subscription to room {}", username, roomId);
                                        throw new org.springframework.messaging.MessagingException("Forbidden: not a participant of this game room");
                                    }
                                } else {
                                    throw new org.springframework.messaging.MessagingException("Invalid game room ID");
                                }
                            }
                        }
                    }
                }
                return message;
            }
        });
    }
}