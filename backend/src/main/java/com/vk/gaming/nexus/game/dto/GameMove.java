package com.vk.gaming.nexus.game.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GameMove {
    private String type;
    private String roomId;
    private String playerUsername;

    @Min(value = 0, message = "Board position must be 0-8")
    @Max(value = 8, message = "Board position must be 0-8")
    private int boardPosition;

    private String symbol;
    private String gameState;
}
