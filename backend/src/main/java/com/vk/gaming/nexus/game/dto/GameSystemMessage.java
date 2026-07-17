package com.vk.gaming.nexus.game.dto;

import lombok.Data;

@Data
public class GameSystemMessage {
    private String type;
    private String payload;
    private String message;
    private String winner;
    private String loser;
    private String sender;
}
