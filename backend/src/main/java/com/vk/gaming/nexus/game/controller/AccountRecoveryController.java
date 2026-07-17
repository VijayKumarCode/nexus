package com.vk.gaming.nexus.game.controller;

import com.vk.gaming.nexus.game.dto.EmailRequest;
import com.vk.gaming.nexus.game.dto.RecoveryRequest;
import com.vk.gaming.nexus.game.service.AccountRecoveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recovery")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AccountRecoveryController {

    private final AccountRecoveryService recoveryService;

    @PostMapping("/send-otp")
    public ResponseEntity<?> requestOtp(@RequestBody @Valid EmailRequest request) {
        try {
            recoveryService.sendOtp(request.getEmail());
            return ResponseEntity.ok(java.util.Map.of("message", "OTP sent to your email."));
        } catch (RuntimeException e) {
            log.warn("OTP request failed for {}: {}", request.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify-username")
    public ResponseEntity<?> verifyUsername(@RequestBody @Valid RecoveryRequest req) {
        try {
            String username = recoveryService.recoverUsername(req.getEmail(), req.getOtp());
            return ResponseEntity.ok(java.util.Map.of("username", username));
        } catch (RuntimeException e) {
            log.warn("Username recovery failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody @Valid RecoveryRequest req) {
        try {
            recoveryService.resetPassword(req.getEmail(), req.getOtp(), req.getNewPassword());
            return ResponseEntity.ok(java.util.Map.of("message", "Password updated successfully."));
        } catch (RuntimeException e) {
            log.warn("Password reset failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
