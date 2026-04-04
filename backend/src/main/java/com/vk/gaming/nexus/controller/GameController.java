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
        log.info("Challenge reply: {} → {} | Status: {}",
                message.getReceiver(), message.getSender(), message.getStatus());

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
        log.info("Toss requested for room: {}", roomId);
        GameSystemMessage result = gameService.processToss(request);
        messagingTemplate.convertAndSend("/topic/game/" + roomId, result);
    }

    /* ══════════════════════════════════════════════════════════
       FIX: Added @Payload so Spring correctly deserializes JSON.
            roomId is now passed explicitly from the path variable
            instead of relying on incomingMove.getRoomId() which
            can be null if Jackson fails to bind it.
            Added try-catch so exceptions are logged, not swallowed.
    ══════════════════════════════════════════════════════════ */
    @MessageMapping("/move/{roomId}")
    public void handleGameMove(@DestinationVariable String roomId,
                               @Payload GameMove incomingMove) {
        log.info("Move received — room={} player={} pos={} symbol={}",
                roomId,
                incomingMove.getPlayerUsername(),
                incomingMove.getBoardPosition(),
                incomingMove.getSymbol());

        // Always override roomId from path variable — never trust the body
        incomingMove.setRoomId(roomId);

        try {
            GameMove processedMove = gameService.processGameMove(incomingMove);
            if (processedMove != null) {
                log.info("Move processed — broadcasting to /topic/game/{}", roomId);
                messagingTemplate.convertAndSend("/topic/game/" + roomId, processedMove);
            } else {
                log.warn("processGameMove returned null for room={} player={}",
                        roomId, incomingMove.getPlayerUsername());
            }
        } catch (Exception e) {
            log.error("Exception in handleGameMove room={}: {}", roomId, e.getMessage(), e);
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
        log.info("Toss decision — room={} payload={} winner={}",
                roomId, msg.getPayload(), msg.getWinner());

        GameSystemMessage response = gameService.processTossDecision(
                roomId,
                msg.getWinner(),
                msg.getLoser(),
                msg.getPayload()
        );

        log.info("Toss decision result — firstPlayer={} type={}",
                response.getPayload(), response.getType());
        messagingTemplate.convertAndSend("/topic/game/" + roomId, response);
    }
}