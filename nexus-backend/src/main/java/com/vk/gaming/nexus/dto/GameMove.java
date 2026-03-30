/*
 * Problem No. #179
 * Difficulty: Easy
 * Description: Clean GameMove DTO for proper WebSocket payload mapping
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1)
 * Space Complexity: O(1)
 */
package com.vk.gaming.nexus.dto;

import lombok.Data;

@Data
public class GameMove {
    private String roomId;
    private String playerUsername;
    private int boardPosition;
    private String symbol;
    private String gameState;
}