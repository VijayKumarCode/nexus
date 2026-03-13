/**
 * Problem No. #143
 * Difficulty: Medium
 * Description: Nexus Game Controller with Persistence and Room Isolation
 * Link: https://github.com/VijayKumarCode/Nexus
 * Time Complexity: O(1)
 * Space Complexity: O(1)
 */
package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.*;
import com.vk.gaming.nexus.service.ChallengeService;
import com.vk.gaming.nexus.service.GameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class GameController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChallengeService challengeService;
    private final GameService gameService;

    @MessageMapping("/challenge")
    public void sendChallenge(ChallengeMessage message) {
        log.info("Challenge sent from {} to {}", message.getSender(), message.getReceiver());
        // IMPROVEMENT: Persist the challenge in PostgreSQL as PENDING
        challengeService.createChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/challenge/reply")
    public void replyChallenge(ChallengeMessage message) {
        log.info("Challenge reply from {}: {}", message.getReceiver(), message.getStatus());
        if (ChallengeMessage.ChallengeStatus.ACCEPTED.equals(message.getStatus())) {
            challengeService.acceptChallenge(message);
        }
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getSender(), message);
    }

    @MessageMapping("/toss/{roomId}")
    public void handleToss(@DestinationVariable String roomId, TossRequest request) {
        log.info("Processing toss for room: {}", roomId);
        GameSystemMessage result = gameService.processToss(request);
        // IMPROVEMENT: Isolated broadcast to specific room
        messagingTemplate.convertAndSend("/topic/game/" + roomId, result);
    }

    @MessageMapping("/move/{roomId}")
    public void handleGameMove(@DestinationVariable String roomId, GameMove incomingMove) {
        GameMove processedMove = gameService.processGameMove(incomingMove);
        if (processedMove != null) {
            messagingTemplate.convertAndSend("/topic/game/" + roomId, processedMove);
        }
    }

    @MessageMapping("/reset/{roomId}")
    public void handleReset(@DestinationVariable String roomId) {
        log.info("Resetting board for room: {}", roomId);
        gameService.resetGame(roomId);

        GameSystemMessage message = new GameSystemMessage();
        message.setType("GAME_RESET");
        message.setMessage("The board has been cleared. New game started!");

        messagingTemplate.convertAndSend("/topic/game/" + roomId, message);
    }
}