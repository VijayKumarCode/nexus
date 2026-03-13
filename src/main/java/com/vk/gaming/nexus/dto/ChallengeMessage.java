package com.vk.gaming.nexus.dto;

import lombok.Data;

@Data
public class ChallengeMessage {
    private String sender;
    private String receiver;
    private String roomId; //
    private ChallengeStatus status;

    public enum ChallengeStatus {
        PENDING, ACCEPTED, REJECTED, CANCELLED
    }
}
