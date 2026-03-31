/**
 * Problem No. #156
 * Difficulty: Medium
 * Description: Added missing WebSocket routing to forward challenge replies back to the original challenger
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
import org.springframework.messaging.handler.annotation.Payload;
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
    public void sendChallenge(@Payload ChallengeMessage message) {
        log.info("Processing challenge: {} -> {}", message.getSender(), message.getReceiver());
        challengeService.createChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/challenge/reply")
    public void handleChallengeReply(@Payload ChallengeMessage message) {
        log.info("Challenge reply: {} -> {} | Status: {}", message.getReceiver(), message.getSender(), message.getStatus());

        // ✅ Clean ALL stale game data before starting fresh game
        if (message.getStatus() == ChallengeStatus.ACCEPTED) {
            gameService.resetGame(message.getRoomId());
        }

        challengeService.respondToChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/toss/{roomId}")
    public void handleToss(@DestinationVariable String roomId, @Payload TossRequest request) {
        request.setRoomId(roomId);
        GameSystemMessage result = gameService.processToss(request);
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

        GameSystemMessage reset = new GameSystemMessage();
        reset.setType("GAME_RESET");
        reset.setMessage("The board has been cleared. New game started!");
        messagingTemplate.convertAndSend("/topic/game/" + roomId, reset);

    }

    @MessageMapping("/game.abort")
    public void handleAbort(@Payload ChallengeMessage message) {
        log.info("Game aborted in room: {} by {}", message.getRoomId(), message.getSender());
        gameService.markPlayersOnlineByRoom(message.getRoomId());
        message.setType(MessageType.GAME_ABORTED);
        message.setStatus(ChallengeStatus.CANCELLED);
        messagingTemplate.convertAndSend("/topic/game/" + message.getRoomId(), message);

        // ✅ Broadcast ONLINE status to lobby for both players
        String[] parts = message.getRoomId().split("_");
        if (parts.length >= 2) {
            messagingTemplate.convertAndSend("/topic/lobby.status",
                    new PlayerStatus(parts[0], "ONLINE"));
            messagingTemplate.convertAndSend("/topic/lobby.status",
                    new PlayerStatus(parts[1], "ONLINE"));
        }
    }

    @MessageMapping("/toss/decision/{roomId}")
    public void handleTossDecision(@DestinationVariable String roomId,
                                   @Payload GameSystemMessage msg) {

        GameSystemMessage response = gameService.processTossDecision(
                roomId,
                msg.getWinner(),
                msg.getLoser(),
                msg.getPayload()   // ✅ correct
        );

        messagingTemplate.convertAndSend("/topic/game/" + roomId, response);
    }
}