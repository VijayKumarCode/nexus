package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.AuthRequest;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.exception.EmailAlreadyRegisteredException;
import com.vk.gaming.nexus.exception.UsernameTakenException;
import com.vk.gaming.nexus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    // 🔥 REGISTER
    @Transactional
    public User registerUser(AuthRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyRegisteredException(request.getEmail());
        }

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new UsernameTakenException(request.getUsername());
        }

        User user = new User();
        user.setFullName(request.getFullName());
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        user.setEnabled(false);
        user.setStatus(User.UserStatus.OFFLINE);
        user.setWins(0);
        user.setLosses(0); // ✅ NEW

        String token = java.util.UUID.randomUUID().toString();
        user.setActivationToken(token);

        User savedUser = userRepository.save(user);

        otpService.sendActivationLink(savedUser.getEmail(), token);

        return savedUser;
    }

    // 🔥 LOGIN (SECURE - no user enumeration)
    @Transactional
    public User loginUser(AuthRequest request) {

        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid username or password.");
        }

        if (!user.isEnabled()) {
            throw new RuntimeException("Account not activated.");
        }

        user.setStatus(User.UserStatus.ONLINE);
        user.setIsOnline(true);
        user.setLastSeen(System.currentTimeMillis());

        return userRepository.save(user);
    }

    // 🔥 LOGOUT
    @Transactional
    public void logoutUser(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setStatus(User.UserStatus.OFFLINE);
            user.setIsOnline(false);
            userRepository.save(user);
        });
    }

    // 🔥 HEARTBEAT (OPTIMIZED)
    @Transactional
    public void heartbeat(String username) {
        int updated = userRepository.updateHeartbeat(
                username,
                System.currentTimeMillis()
        );

        if (updated == 0) {
            log.warn("Heartbeat failed: user not found {}", username);
        }
    }

    // 🔥 PRESENCE CLEANUP (SCALABLE)
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void updateIdleUsers() {
        long cutoff = System.currentTimeMillis() - 120000;

        int updated = userRepository.markInactiveUsersOffline(cutoff);

        if (updated > 0) {
            log.info("Marked {} users OFFLINE due to inactivity", updated);
        }
    }

    // 🔥 GAME STATS (OPTIMIZED - NO ENTITY LOAD)
    @Transactional
    public void incrementWins(String username) {
        userRepository.incrementWins(username);
    }

    @Transactional
    public void incrementLosses(String username) {
        userRepository.incrementLosses(username);
    }

    // 🔥 FETCH ONLINE USERS
    public List<User> getOnlineUsers() {
        return userRepository.findByStatus(User.UserStatus.ONLINE);
    }

    // 🔥 USERNAME CHECK
    public boolean isUsernameAvailable(String username) {
        return username != null
                && !username.trim().isEmpty()
                && !userRepository.existsByUsername(username.trim());
    }

    // 🔥 SYNC PRESENCE
    @Transactional
    public void syncUserPresence(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(User.UserStatus.ONLINE);
            u.setIsOnline(true);
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }

    public void resendActivationLink(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEnabled()) {
            throw new RuntimeException("Account already activated");
        }

        String token = UUID.randomUUID().toString();
        user.setActivationToken(token);
        userRepository.save(user);

        otpService.sendActivationLink(email, token);
    }
}