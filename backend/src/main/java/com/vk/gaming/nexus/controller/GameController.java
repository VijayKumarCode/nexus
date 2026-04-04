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
        log.info("Challenge: {} → {}", message.getSender(), message.getReceiver());
        challengeService.createChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/challenge/reply")
    public void handleChallengeReply(@Payload ChallengeMessage message) {
        log.info("Challenge reply: {} → {} | {}", message.getReceiver(), message.getSender(), message.getStatus());
        if (message.getStatus() == ChallengeStatus.ACCEPTED) {
            gameService.resetGame(message.getRoomId());
        }
        challengeService.respondToChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/toss/{roomId}")
    public void handleToss(@DestinationVariable String roomId,
                           @Payload TossRequest request) {
        request.setRoomId(roomId);
        log.info("Toss requested — room={}", roomId);
        GameSystemMessage result = gameService.processToss(request);
        messagingTemplate.convertAndSend("/topic/game/" + roomId, result);
    }

    @MessageMapping("/move/{roomId}")
    public void handleGameMove(@DestinationVariable String roomId,
                               @Payload GameMove incomingMove) {
        log.info("Move received — room={} player={} pos={}",
                roomId, incomingMove.getPlayerUsername(), incomingMove.getBoardPosition());
        incomingMove.setRoomId(roomId);  // always override from path variable

        try {
            GameMove processedMove = gameService.processGameMove(incomingMove);
            if (processedMove != null) {
                log.info("Broadcasting move to /topic/game/{}", roomId);
                messagingTemplate.convertAndSend("/topic/game/" + roomId, processedMove);
            } else {
                log.warn("processGameMove returned null — room={} player={}",
                        roomId, incomingMove.getPlayerUsername());
            }
        } catch (Exception e) {
            log.error("Exception in handleGameMove room={}: {}", roomId, e.getMessage(), e);
        }
    }

    @MessageMapping("/reset/{roomId}")
    public void handleReset(@DestinationVariable String roomId) {
        log.info("Reset board — room={}", roomId);
        gameService.resetGame(roomId);
        GameSystemMessage reset = new GameSystemMessage();
        reset.setType("GAME_RESET");
        reset.setMessage("Board cleared. New game started!");
        messagingTemplate.convertAndSend("/topic/game/" + roomId, reset);
    }

    @MessageMapping("/game.abort")
    public void handleAbort(@Payload ChallengeMessage message) {
        log.info("Game aborted — room={} by={}", message.getRoomId(), message.getSender());
        gameService.markPlayersOnlineByRoom(message.getRoomId());
        message.setType(MessageType.GAME_ABORTED);
        message.setStatus(ChallengeStatus.CANCELLED);
        messagingTemplate.convertAndSend("/topic/game/" + message.getRoomId(), message);

        String[] parts = message.getRoomId().split("_");
        if (parts.length >= 2) {
            messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(parts[0], "ONLINE"));
            messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(parts[1], "ONLINE"));
        }
    }

    /* BUG 15 FIX: no longer passing winner/loser from client to service —
       those values were always ignored. Now passes only what matters: roomId + choice. */
    @MessageMapping("/toss/decision/{roomId}")
    public void handleTossDecision(@DestinationVariable String roomId,
                                   @Payload GameSystemMessage msg) {
        log.info("Toss decision — room={} payload(choice)={}", roomId, msg.getPayload());
        GameSystemMessage response = gameService.processTossDecision(roomId, msg.getPayload());
        log.info("Toss result — firstPlayer={}", response.getPayload());
        messagingTemplate.convertAndSend("/topic/game/" + roomId, response);
    }
}