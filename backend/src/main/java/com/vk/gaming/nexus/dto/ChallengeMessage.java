package com.vk.gaming.nexus.dto;

import com.vk.gaming.nexus.enums.ChallengeStatus;
import com.vk.gaming.nexus.enums.MessageType;
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

