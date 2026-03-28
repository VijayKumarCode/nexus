/**
 * Problem No. #196
 * Difficulty: Medium
 * Description: Cleaned up UserController mappings and standardized exception handling.
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1) per endpoint
 * Space Complexity: O(1)
 */
package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.AuthRequest;
import com.vk.gaming.nexus.dto.EmailRequest;
import com.vk.gaming.nexus.dto.PlayerStatus;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.repository.UserRepository;
import com.vk.gaming.nexus.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        try {
            User user = userService.registerUser(request);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            // Returns a JSON-friendly error instead of raw text, easier for frontend to parse
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/activate")
    public ResponseEntity<?> activateAccount(@RequestParam String token) {
        return userRepository.findByActivationToken(token)
                .map(user -> {
                    user.setEnabled(true);
                    user.setActivationToken(null);
                    userRepository.save(user);
                    // Returning HTML or a redirect is usually better here so the user isn't staring at raw JSON
                    return ResponseEntity.ok(java.util.Map.of("message", "Account activated successfully!"));
                })
                .orElse(ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid or expired link.")));
    }

    @PostMapping("/resend-activation")
    public ResponseEntity<?> resendActivation(@RequestBody EmailRequest request) {
        userService.resendActivationLink(request.getEmail());
        return ResponseEntity.ok(java.util.Map.of("message", "Activation link resent."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        try {
            User user = userService.loginUser(request);
            messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(user.getUsername(), "ONLINE"));
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout/{username}")
    public ResponseEntity<?> logout(@PathVariable String username) {
        userService.logoutUser(username);
        messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(username, "OFFLINE"));
        return ResponseEntity.ok(java.util.Map.of("message", username + " logged out"));
    }

    // Changed to @PostMapping. Having an endpoint respond to state changes via GET is against REST principles.
    @PostMapping("/sync")
    public ResponseEntity<Void> syncPresence(@RequestParam String username) {
        userService.syncUserPresence(username);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestParam String username) {
        userService.heartbeat(username);
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
    public ResponseEntity<List<User>> getLeaderboard() {
        return ResponseEntity.ok(userRepository.findTop10ByOrderByWinsDesc());
    }
}