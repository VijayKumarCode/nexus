package com.vk.gaming.nexus.game.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TossRequest {
    @NotBlank(message = "Player one is required")
    private String playerOne;

    @NotBlank(message = "Player two is required")
    private String playerTwo;

    @NotBlank(message = "Room ID is required")
    private String roomId;
}
