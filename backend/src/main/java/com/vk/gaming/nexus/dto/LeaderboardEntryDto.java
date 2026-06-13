package com.vk.gaming.nexus.dto;

import com.vk.gaming.nexus.enums.UserStatus;
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

