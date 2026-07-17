package com.vk.gaming.nexus.game.dto;

import com.vk.gaming.nexus.game.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeaderboardEntryDto {
    private String username;
    private String displayName;
    private UserStatus status;
    private int wins;
    private int losses;
    private int totalGames;
    private double winRate;
}

