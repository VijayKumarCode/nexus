package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.*;
import com.vk.gaming.nexus.enums.ChallengeStatus;
import com.vk.gaming.nexus.enums.MessageType;
import com.vk.gaming.nexus.enums.UserStatus;
import com.vk.gaming.nexus.service.ChallengeService;
import com.vk.gaming.nexus.service.GameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class GameController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChallengeService challengeService;
    private final GameService gameService;

    @MessageMapping("/challenge")
    public void sendChallenge(@Payload @Valid ChallengeMessage message, Principal principal) {
        String sender = principal.getName();
        message.setSender(sender);
        log.info("Challenge: {} -> {}", sender, message.getReceiver());
        challengeService.createChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/challenge/reply")
    public void handleChallengeReply(@Payload @Valid ChallengeMessage message, Principal principal) {
        String replier = principal.getName();
        log.info("Challenge reply from {}: status={}", replier, message.getStatus());
        if (message.getStatus() == ChallengeStatus.ACCEPTED) {
            gameService.resetGame(message.getRoomId());
        }
        challengeService.respondToChallenge(message);
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/toss/{roomId}")
    public void handleToss(@DestinationVariable String roomId,
                           @Payload @Valid TossRequest request,
                           Principal principal) {
        if (!gameService.isRoomParticipant(roomId, principal.getName())) {
            log.warn("Unauthorized toss attempt by {} in room {}", principal.getName(), roomId);
            return;
        }
        request.setRoomId(roomId);
        log.info("Toss requested — room={}", roomId);
        GameSystemMessage result = gameService.processToss(request);
        messagingTemplate.convertAndSend("/topic/game/" + roomId, result);
    }

    @MessageMapping("/move/{roomId}")
    public void handleGameMove(@DestinationVariable String roomId,
                               @Payload @Valid GameMove incomingMove,
                               Principal principal) {
        String username = principal.getName();
        if (!gameService.isRoomParticipant(roomId, username)) {
            log.warn("Unauthorized move attempt by {} in room {}", username, roomId);
            return;
        }
        incomingMove.setRoomId(roomId);
        incomingMove.setPlayerUsername(username);

        try {
            GameMove processedMove = gameService.processGameMove(incomingMove);
            if (processedMove != null) {
                log.info("Broadcasting move to /topic/game/{}", roomId);
                messagingTemplate.convertAndSend("/topic/game/" + roomId, processedMove);
            } else {
                log.warn("processGameMove returned null — room={} player={}", roomId, username);
            }
        } catch (Exception e) {
            log.error("Exception in handleGameMove room={}: {}", roomId, e.getMessage(), e);
        }
    }

    @MessageMapping("/reset/{roomId}")
    public void handleReset(@DestinationVariable String roomId, Principal principal) {
        if (!gameService.isRoomParticipant(roomId, principal.getName())) {
            log.warn("Unauthorized reset attempt by {} in room {}", principal.getName(), roomId);
            return;
        }
        log.info("Reset board — room={}", roomId);
        gameService.resetGame(roomId);
        GameSystemMessage reset = new GameSystemMessage();
        reset.setType("GAME_RESET");
        reset.setMessage("Board cleared. New game started!");
        messagingTemplate.convertAndSend("/topic/game/" + roomId, reset);
    }

    @MessageMapping("/game.abort")
    public void handleAbort(@Payload @Valid ChallengeMessage message, Principal principal) {
        String username = principal.getName();
        if (!gameService.isRoomParticipant(message.getRoomId(), username)) {
            log.warn("Unauthorized abort attempt by {} in room {}", username, message.getRoomId());
            return;
        }
        log.info("Game aborted — room={} by={}", message.getRoomId(), username);
        gameService.markPlayersOnlineByRoom(message.getRoomId());
        message.setType(MessageType.GAME_ABORTED);
        message.setStatus(ChallengeStatus.CANCELLED);
        message.setSender(username);
        messagingTemplate.convertAndSend("/topic/game/" + message.getRoomId(), message);

        String[] parts = message.getRoomId().split("_");
        if (parts.length >= 2) {
            messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(parts[0], UserStatus.ONLINE));
            messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(parts[1], UserStatus.ONLINE));
        }
    }

    @MessageMapping("/toss/decision/{roomId}")
    public void handleTossDecision(@DestinationVariable String roomId,
                                   @Payload GameSystemMessage msg,
                                   Principal principal) {
        if (!gameService.isRoomParticipant(roomId, principal.getName())) {
            log.warn("Unauthorized toss decision by {} in room {}", principal.getName(), roomId);
            return;
        }
        log.info("Toss decision — room={} choice={}", roomId, msg.getPayload());
        GameSystemMessage response = gameService.processTossDecision(roomId, msg.getPayload());
        log.info("Toss result — firstPlayer={}", response.getPayload());
        messagingTemplate.convertAndSend("/topic/game/" + roomId, response);
    }
}
