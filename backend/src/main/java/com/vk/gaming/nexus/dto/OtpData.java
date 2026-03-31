package com.vk.gaming.nexus.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OtpData {
    private String otp;
    private long createdAt;
}