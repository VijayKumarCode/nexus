package com.vk.gaming.nexus.dto;

import lombok.Data;

@Data
public class RecoveryRequest {
    private String email;
    private String otp;
    private String newPassword;
}
