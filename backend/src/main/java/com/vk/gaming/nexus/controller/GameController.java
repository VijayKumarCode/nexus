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

        // FIX NEXUS-008: Send response to the ORIGINAL SENDER (challenger), not receiver
        String notifyUser = message.getSender();
        messagingTemplate.convertAndSend(
                "/topic/challenges/" + notifyUser,
                message
        );
    }

    @MessageMapping("/toss/{roomId}")
    public void handleToss(@DestinationVariable String roomId, Principal principal) {
        String username = principal.getName();

        // FIX NEXUS-009: Validate roomId format and pre-register if needed
        String[] players = roomId.split("_");
        if (players.length != 2) {
            log.warn("Invalid roomId format: {}", roomId);
            return;
        }

        // Ensure room is registered (handles race condition where accept hasn't completed)
        gameService.ensureRoomRegistered(roomId, players[0], players[1]);

        // 1. Verify authorization
        if (!gameService.isRoomParticipant(roomId, username)) {
            log.warn("Unauthorized toss attempt by {} in room {}", username, roomId);
            return;
        }

        // 2. Construct the required TossRequest DTO
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

    @MessageMapping("/game/rematch/{roomId}")
    public void handleRematch(@DestinationVariable String roomId, Principal principal) {
        String username = principal.getName();
        if (!gameService.isRoomParticipant(roomId, username)) {
            log.warn("Unauthorized rematch attempt by {} in room {}", username, roomId);
            return;
        }
        log.info("Rematch request — room={} player={}", roomId, username);
        GameSystemMessage response = gameService.processRematch(roomId, username);
        if (response != null) {
            messagingTemplate.convertAndSend("/topic/game/" + roomId, response);
        }
    }

    @MessageMapping("/game/abort")
    public void handleAbort(@Payload ChallengeMessage message, Principal principal) {
        String username = principal.getName();
        if (!gameService.isRoomParticipant(message.getRoomId(), username)) {
            log.warn("Unauthorized game abort attempt by {} in room {}", username, message.getRoomId());
            return;
        }

        log.info("========== GAME ABORT ==========");
        log.info("room={}", message.getRoomId());
        log.info("player={}", username);

        // FIX NEXUS-013: markPlayersOnlineByRoom already resets users internally
        gameService.markPlayersOnlineByRoom(message.getRoomId());

        log.info("markPlayersOnlineByRoom completed");

        // FIX NEXUS-008: Use GameSystemMessage for game topic instead of ChallengeMessage
        GameSystemMessage abortMsg = new GameSystemMessage();
        abortMsg.setType("GAME_ABORTED");
        abortMsg.setMessage(username + " aborted the game.");
        abortMsg.setWinner(username);
        abortMsg.setPayload(username);

        messagingTemplate.convertAndSend(
                "/topic/game/" + message.getRoomId(),
                abortMsg
        );

        String[] parts = message.getRoomId().split("_");

        log.info("room split={}", java.util.Arrays.toString(parts));

        if (parts.length >= 2) {
            log.info("Broadcasting ONLINE status for {} and {}", parts[0], parts[1]);

            // FIX NEXUS-013: Removed redundant resetUser calls — already done by markPlayersOnlineByRoom

            messagingTemplate.convertAndSend(
                    "/topic/lobby.status",
                    new PlayerStatus(parts[0], UserStatus.ONLINE)
            );

            messagingTemplate.convertAndSend(
                    "/topic/lobby.status",
                    new PlayerStatus(parts[1], UserStatus.ONLINE)
            );
        }

        log.info("========== END ABORT ==========");
    }

    // FIX NEXUS-010: Use @DestinationVariable for roomId instead of msg.getMessage()
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
