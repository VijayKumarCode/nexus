package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.AuthRequest;
import com.vk.gaming.nexus.dto.AuthResponse;
import com.vk.gaming.nexus.dto.EmailRequest;
import com.vk.gaming.nexus.dto.LeaderboardEntryDto;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.enums.UserStatus;
import com.vk.gaming.nexus.service.JwtService;
import com.vk.gaming.nexus.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
@Slf4j
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody @Valid AuthRequest request) {
        try {
            User user = userService.registerUser(request);
            return ResponseEntity.ok(Map.of(
                    "username", user.getUsername(),
                    "message", "Registration successful. Please check your email to activate your account."
            ));
        } catch (RuntimeException e) {
            log.warn("Registration failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/activate")
    public ResponseEntity<?> activateAccount(@RequestParam String token) {
        boolean activated = userService.activateAccount(token);
        if (activated) {
            return ResponseEntity.ok(Map.of("message", "Account activated successfully! You can now log in."));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired activation link."));
    }

    @PostMapping("/resend-activation")
    public ResponseEntity<?> resendActivation(@RequestBody @Valid EmailRequest request) {
        try {
            userService.resendActivationLink(request.getEmail());
            return ResponseEntity.ok(Map.of("message", "Activation link resent. Check your email."));
        } catch (RuntimeException e) {
            log.warn("Resend activation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid AuthRequest request) {
        try {
            User user = userService.loginUser(request);
            String token = jwtService.generateToken(user.getUsername());
            return ResponseEntity.ok(new AuthResponse(token, user));
        } catch (RuntimeException e) {
            log.warn("Login failed for user {}: {}", request.getUsername(), e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SECURE: Uses Authentication principal instead of path variable.
     * Users can only log themselves out.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(Authentication authentication) {
        String username = authentication.getName();
        userService.logoutUser(username);
        return ResponseEntity.ok(Map.of("message", username + " logged out"));
    }

    /**
     * SECURE: Uses Authentication principal to prevent spoofing.
     */
    @PostMapping("/sync")
    public ResponseEntity<Void> syncPresence(Authentication authentication) {
        userService.syncUserPresence(authentication.getName());
        return ResponseEntity.ok().build();
    }

    /**
     * SECURE: Uses Authentication principal to prevent spoofing.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(Authentication authentication) {
        userService.heartbeat(authentication.getName());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/lobby")
    public ResponseEntity<List<User>> getLobby() {
        return ResponseEntity.ok(userService.getOnlineUsers());
    }

    @GetMapping("/check-username")
    public ResponseEntity<Boolean> checkUsername(@RequestParam String username) {
        return ResponseEntity.ok(userService.isUsernameAvailable(username));
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<LeaderboardEntryDto>> getLeaderboard() {
        return ResponseEntity.ok(userService.getTopPlayers(10));
    }

    /**
     * Health check with database connectivity verification.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbHealthy = userService.isDatabaseHealthy();
        Map<String, Object> health = Map.of(
                "status", dbHealthy ? "UP" : "DEGRADED",
                "app", "Nexus Multiplayer Arena",
                "version", "1.1.0",
                "database", dbHealthy ? "CONNECTED" : "UNREACHABLE"
        );
        return dbHealthy ? ResponseEntity.ok(health) : ResponseEntity.status(503).body(health);
    }
}
