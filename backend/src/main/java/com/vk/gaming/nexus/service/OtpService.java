package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.config.AppProperties;
import com.vk.gaming.nexus.dto.OtpData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${RESEND_API_KEY}")
    private String resendApiKey;

    @Value("${otp.expiry-minutes:10}")
    private long otpExpiryMinutes;

    @Value("${otp.rate-limit-per-email:3}")
    private int rateLimitPerEmail;

    @Value("${otp.rate-limit-window-minutes:60}")
    private int rateLimitWindowMinutes;

    private static final String RESEND_URL = "https://api.resend.com/emails";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> otpRequestLog = new ConcurrentHashMap<>();

    private void sendEmail(String to, String subject, String textBody) {
        log.info("Sending email to={} subject={}", to, subject);

        String htmlBody = textBody
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");

        Map<String, Object> payload = new HashMap<>();
        payload.put("from", appProperties.getMailFrom());
        payload.put("to", Collections.singletonList(to));
        payload.put("subject", subject);
        payload.put("text", textBody);
        payload.put("html", "<p>" + htmlBody + "</p>");

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize email payload: {}", e.getMessage());
            throw new RuntimeException("Email payload serialization failed", e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resendApiKey);

        HttpEntity<String> request = new HttpEntity<>(json, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(RESEND_URL, request, String.class);
            log.info("Email sent — status={}", response.getStatusCode());
        } catch (HttpClientErrorException e) {
            log.error("Resend rejected — status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Email delivery failed: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Resend call failed: {}", e.getMessage());
            throw new RuntimeException("Email delivery failed: " + e.getMessage());
        }
    }

    public String generateOtp(String email) {
        checkRateLimit(email);
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        otpStorage.put(email, new OtpData(otp, System.currentTimeMillis()));
        logRequest(email);
        return otp;
    }

    public void sendOtp(String email, String otp) {
        sendEmail(email,
                "Nexus Multiplayer — Verification Code",
                "Your OTP is: " + otp + "\n\nValid for " + otpExpiryMinutes + " minutes.\n\nDo not share this code.");
        log.info("OTP sent to {}", email);
    }

    public boolean verifyOtp(String email, String inputOtp) {
        OtpData data = otpStorage.get(email);
        if (data == null) return false;

        long otpExpiryMs = otpExpiryMinutes * 60 * 1000L;
        if (System.currentTimeMillis() - data.getCreatedAt() > otpExpiryMs) {
            otpStorage.remove(email);
            log.warn("OTP expired for {}", email);
            return false;
        }

        if (!data.getOtp().equals(inputOtp)) return false;

        otpStorage.remove(email);
        return true;
    }

    public void generateAndSendOtp(String email) {
        String otp = generateOtp(email);
        sendOtp(email, otp);
        log.info("OTP generated and sent for {}", email);
    }

    public void sendActivationLink(String email, String token) {
        String url = appProperties.getBaseUrl() + "/api/users/activate?token=" + token;
        String body = "Welcome to Nexus Multiplayer!\n\n"
                + "Click the link below to activate your account:\n"
                + url + "\n\n"
                + "This link expires in 24 hours.\n\n"
                + "If you did not register, ignore this email.";

        sendEmail(email, "Activate Your Nexus Account", body);
        log.info("Activation email sent to {}", email);
    }

    private void checkRateLimit(String email) {
        long windowStart = System.currentTimeMillis() - (rateLimitWindowMinutes * 60 * 1000L);
        List<Long> requests = otpRequestLog.getOrDefault(email, new ArrayList<>());
        long recentRequests = requests.stream().filter(ts -> ts > windowStart).count();

        if (recentRequests >= rateLimitPerEmail) {
            log.warn("OTP rate limit exceeded for email: {}", email);
            throw new RuntimeException("Too many OTP requests. Please try again later.");
        }
    }

    private void logRequest(String email) {
        otpRequestLog.computeIfAbsent(email, k -> new ArrayList<>()).add(System.currentTimeMillis());
    }

    @Scheduled(fixedRate = 600_000)
    public void cleanExpiredOtps() {
        long now = System.currentTimeMillis();
        long otpExpiryMs = otpExpiryMinutes * 60 * 1000L;
        int was = otpStorage.size();
        otpStorage.entrySet().removeIf(e -> now - e.getValue().getCreatedAt() > otpExpiryMs);
        int removed = was - otpStorage.size();
        if (removed > 0) log.info("Cleaned {} expired OTPs", removed);

        // Also clean rate limit logs older than window
        long windowStart = now - (rateLimitWindowMinutes * 60 * 1000L);
        otpRequestLog.replaceAll((k, v) -> {
            v.removeIf(ts -> ts < windowStart);
            return v;
        });
        otpRequestLog.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
