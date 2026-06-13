package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.AuthRequest;
import com.vk.gaming.nexus.dto.LeaderboardEntryDto;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.enums.UserStatus;
import com.vk.gaming.nexus.exceptions.EmailAlreadyRegisteredException;
import com.vk.gaming.nexus.exceptions.UsernameTakenException;
import com.vk.gaming.nexus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    @Value("${presence.idle-threshold-ms:120000}")
    private long idleThresholdMs;

    @Value("${presence.check-rate-ms:10000}")
    private long presenceCheckRateMs;

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
        user.setStatus(UserStatus.OFFLINE);
        user.setWins(0);
        user.setLosses(0);

        String token = UUID.randomUUID().toString();
        user.setActivationToken(token);

        User saved = userRepository.save(user);
        otpService.sendActivationLink(saved.getEmail(), token);
        return saved;
    }

    @Transactional
    public boolean activateAccount(String token) {
        return userRepository.findByActivationToken(token)
                .map(user -> {
                    user.setEnabled(true);
                    user.setActivationToken(null);
                    userRepository.save(user);
                    log.info("Account activated for user: {}", user.getUsername());
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public User loginUser(AuthRequest request) {
        User user = userRepository.findByUsername(request.getUsername()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid username or password.");
        }
        if (!user.isEnabled()) {
            throw new RuntimeException("Account not activated. Check your email.");
        }

        user.setStatus(UserStatus.ONLINE);
        user.setLastSeen(System.currentTimeMillis());
        return userRepository.save(user);
    }

    @Transactional
    public void logoutUser(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setStatus(UserStatus.OFFLINE);
            userRepository.save(user);
            log.info("User logged out: {}", username);
        });
    }

    @Transactional
    public void syncUserPresence(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            if (u.getStatus() != UserStatus.IN_GAME) {
                u.setStatus(UserStatus.ONLINE);
            }
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }

    @Transactional
    public void heartbeat(String username) {
        int updated = userRepository.updateHeartbeat(
                username,
                System.currentTimeMillis(),
                UserStatus.IN_GAME,
                UserStatus.ONLINE
        );
        if (updated == 0) {
            log.warn("Heartbeat: user not found — {}", username);
        }
    }

    @Scheduled(fixedDelayString = "${presence.check-rate-ms:10000}")
    @Transactional
    public void updateIdleUsers() {
        long cutoff = System.currentTimeMillis() - idleThresholdMs;
        int updated = userRepository.markInactiveUsersOffline(cutoff, UserStatus.OFFLINE);
        if (updated > 0) {
            log.info("Marked {} user(s) OFFLINE due to inactivity", updated);
        }
    }

    @Transactional
    public void incrementWins(String username) {
        userRepository.incrementWins(username);
    }

    @Transactional
    public void incrementLosses(String username) {
        userRepository.incrementLosses(username);
    }

    public List<User> getOnlineUsers() {
        return userRepository.findByStatus(UserStatus.ONLINE);
    }

    public List<LeaderboardEntryDto> getTopPlayers(int limit) {
        List<User> users = userRepository.findTop10ActivePlayers();
        return users.stream()
                .limit(limit)
                .map(u -> new LeaderboardEntryDto(
                        u.getUsername(),
                        u.getFullName(),
                        u.getStatus(),
                        u.getWins(),
                        u.getLosses(),
                        u.getWins() + u.getLosses(),
                        calculateWinRate(u.getWins(), u.getLosses())
                ))
                .collect(Collectors.toList());
    }

    private double calculateWinRate(int wins, int losses) {
        int total = wins + losses;
        return total == 0 ? 0.0 : Math.round((double) wins / total * 10000.0) / 100.0;
    }

    public boolean isUsernameAvailable(String username) {
        return username != null
                && !username.trim().isEmpty()
                && !userRepository.existsByUsername(username.trim());
    }

    @Transactional
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

    public boolean isDatabaseHealthy() {
        try {
            userRepository.count();
            return true;
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
