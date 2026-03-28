package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.EmailRequest;
import com.vk.gaming.nexus.dto.RecoveryRequest;
import com.vk.gaming.nexus.service.AccountRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recovery")
@RequiredArgsConstructor
public class AccountRecoveryController {

    private final AccountRecoveryService recoveryService;

    @PostMapping("/send-otp")
    public ResponseEntity<String> requestOtp(@RequestBody EmailRequest request) {
        recoveryService.sendOtp(request.getEmail());
        return ResponseEntity.ok("OTP sent to your email.");
    }

    @PostMapping("/verify-username")
    public ResponseEntity<String> verifyUsername(@RequestBody RecoveryRequest req) {
        String username = recoveryService.recoverUsername(
                req.getEmail(),
                req.getOtp()
        );
        return ResponseEntity.ok(username);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody RecoveryRequest req) {

        if (req.getNewPassword() == null || req.getNewPassword().isBlank()) {
            return ResponseEntity.badRequest().body("New password required");
        }

        recoveryService.resetPassword(
                req.getEmail(),
                req.getOtp(),
                req.getNewPassword()
        );

        return ResponseEntity.ok("Password updated");
    }
}