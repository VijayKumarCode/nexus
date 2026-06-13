package com.vk.gaming.nexus.dto;

import com.vk.gaming.nexus.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private User user;
}

