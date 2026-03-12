package com.vk.gaming.nexus.dto;

import lombok.Data;

@Data
public class ChallengeMessage {
    private String sender;
    private String receiver;
    private String roomId; //
    private ChallengeType type;

    private enum ChallengeType {
        CHALLENGE,
        ACCEPT,
        REJECT;
    }
}
