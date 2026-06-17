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
    public void handleChallengeReply(
            @Payload @Valid ChallengeMessage message,
            Principal principal) {

        String replier = principal.getName();

        log.info(
                "Challenge reply from {}: status={}",
                replier,
                message.getStatus()
        );

        if (message.getStatus() == ChallengeStatus.ACCEPTED) {

            gameService.resetGame(message.getRoomId());

            gameService.registerRoom(
                    message.getRoomId(),
                    message.getSender(),
                    message.getReceiver()
            );
        }

        challengeService.respondToChallenge(message);

        messagingTemplate.convertAndSend(
                "/topic/challenges/" + message.getReceiver(),
                message
        );
    }

    @MessageMapping("/toss/{roomId}")
    public void handleToss(@DestinationVariable String roomId, Principal principal) {
        String username = principal.getName();

        // 1. Verify authorization
        if (!gameService.isRoomParticipant(roomId, username)) {
            log.warn("Unauthorized toss attempt by {} in room {}", username, roomId);
            return;
        }

        // 2. Construct the required TossRequest DTO
        // We split the room ID to identify the two players
        String[] players = roomId.split("_");
        if (players.length < 2) return;

        TossRequest tossRequest = new TossRequest();
        tossRequest.setRoomId(roomId);
        tossRequest.setPlayerOne(players[0]);
        tossRequest.setPlayerTwo(players[1]);

        log.info("Toss initiated — room={} by={}", roomId, username);

        // 3. Call the correct service method
        GameSystemMessage tossMessage = gameService.processToss(tossRequest);

        messagingTemplate.convertAndSend("/topic/game/" + roomId, tossMessage);
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

    @MessageMapping("/game/abort")
    public void handleAbort(@Payload ChallengeMessage message, Principal principal) {
        String username = principal.getName();
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
