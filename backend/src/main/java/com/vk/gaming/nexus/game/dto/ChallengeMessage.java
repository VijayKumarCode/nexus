package com.vk.gaming.nexus.game.dto;

import com.vk.gaming.nexus.game.enums.ChallengeStatus;
import com.vk.gaming.nexus.game.enums.MessageType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChallengeMessage {
    @NotBlank(message = "Sender is required")
    private String sender;

    @NotBlank(message = "Receiver is required")
    private String receiver;

    @NotBlank(message = "Room ID is required")
    private String roomId;

    private ChallengeStatus status;
    private MessageType type;
}

