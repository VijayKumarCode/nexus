/**
 * Problem No. #197
 * Difficulty: Medium
 * Description: OtpService with Resend configuration and automated memory cleanup.
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1) for generation/verification, O(n) for periodic cleanup
 * Space Complexity: O(u) where u is active unverified users
 */
package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.config.AppConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final JavaMailSender mailSender;
    private final AppConfig appConfig;

    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();
    private static final long OTP_EXPIRY = 10 * 60 * 1000; // 10 minutes
    private static final SecureRandom secureRandom = new SecureRandom();

    public String generateOtp(String email) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        otpStorage.put(email, new OtpData(otp, System.currentTimeMillis()));
        return otp;
    }

    public void sendOtp(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("vkumar.kumar31@gmail.com");
        message.setTo(email);
        message.setSubject("Nexus Multiplayer - Verification Code");
        message.setText("Your OTP is: " + otp + "\n\nValid for 10 minutes.");

        try {
            mailSender.send(message);
            log.info("OTP sent to {}", email);
        } catch (Exception e) {
            log.error("SMTP Error sending OTP to {}: {}", email, e.getMessage());
        }
    }

    public boolean verifyOtp(String email, String inputOtp) {
        OtpData data = otpStorage.get(email);

        if (data == null) {
            return false;
        }

        if (System.currentTimeMillis() - data.getCreatedAt() > OTP_EXPIRY) {
            otpStorage.remove(email);
            log.warn("OTP expired for {}", email);
            return false;
        }

        if (!data.getOtp().equals(inputOtp)) {
            return false;
        }

        otpStorage.remove(email);
        return true;
    }

    public void generateAndSendOtp(String email) {
        String otp = generateOtp(email);
        sendOtp(email, otp);
        log.info("OTP generated + sent for {}", email);
    }

    public void sendActivationLink(String email, String token) {
        log.info(">>> BASE URL FROM CONFIG = {}", appConfig.getBaseUrl());
        String activationUrl = appConfig.getBaseUrl() + "/api/users/activate?token=" + token
                + "&ngrok-skip-browser-warning=true";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("vkumar.kumar31@gmail.com");
        message.setTo(email);
        message.setSubject("Activate Your Nexus Account");
        message.setText("Welcome to Nexus Multiplayer!\n\n" +
                "Click the link below to activate your account:\n" +
                activationUrl + "\n\n" +
                "This link will expire soon.");

        try {
            mailSender.send(message);
            log.info("Activation email dispatched via Resend to {}", email);
        } catch (Exception e) {
            log.error("Activation email failed to send: {}", e.getMessage());
        }
    }

    // 🔥 Added memory cleanup to prevent Map from growing infinitely
    @Scheduled(fixedRate = 600000) // Runs every 10 minutes
    public void cleanExpiredOtps() {
        long now = System.currentTimeMillis();
        int initialSize = otpStorage.size();

        otpStorage.entrySet().removeIf(entry -> now - entry.getValue().getCreatedAt() > OTP_EXPIRY);

        int removedCount = initialSize - otpStorage.size();
        if (removedCount > 0) {
            log.info("Cleaned up {} expired OTPs from memory.", removedCount);
        }
    }

    @Data
    @AllArgsConstructor
    static class OtpData {
        private String otp;
        private long createdAt;
    }
}