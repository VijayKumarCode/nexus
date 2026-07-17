package com.vk.gaming.nexus.game.dto;

import com.vk.gaming.nexus.game.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PlayerStatus {
    private String username;
    private UserStatus status;
}

