package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.ChallengeMessage;
import com.vk.gaming.nexus.dto.GameMove;
import com.vk.gaming.nexus.dto.GameSystemMessage;
import com.vk.gaming.nexus.dto.TossRequest;
import com.vk.gaming.nexus.service.GameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class GameController {

    private final SimpMessagingTemplate messagingTemplate;
    private final GameService gameService; // Injected service

    @MessageMapping("/challenge")
    public void sendChallenge(ChallengeMessage message) {
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getReceiver(), message);
    }

    @MessageMapping("/challenge/reply")
    public void replyChallenge(ChallengeMessage message) {
        messagingTemplate.convertAndSend("/topic/challenges/" + message.getSender(), message);
    }

    @MessageMapping("/toss")
    @SendTo("/topic/game.system")
    public GameSystemMessage handleToss(TossRequest request) {
        log.info("Processing toss request between {} and {}", request.getPlayerOne(), request.getPlayerTwo());
        return gameService.processToss(request);
    }

    @MessageMapping("/pass")
    @SendTo("/topic/game.system")
    public GameSystemMessage handlePass(String passingPlayer) {
        log.info("Player {} is passing the turn", passingPlayer);
        return gameService.processPass(passingPlayer);
    }

    // Listens for moves sent to /app/move/{roomId}
    @MessageMapping("/move/{roomId}")
    public void handleGameMove(@DestinationVariable String roomId, GameMove incomingMove) {

        // Process and validate the move in the service layer
        GameMove processedMove = gameService.processGameMove(incomingMove);

        // If the move is valid (not null), broadcast it to the specific room topic
        if (processedMove != null) {
            messagingTemplate.convertAndSend("/topic/game/" + roomId, processedMove);
        }
    }

    @MessageMapping("/reset")
    @SendTo("/topic/game.system") // Broadcast to the system channel
    public GameSystemMessage handleReset() {
        gameService.resetGame();

        GameSystemMessage message = new GameSystemMessage();
        message.setType("GAME_RESET");
        message.setMessage("The board has been cleared. New game started!");
        return message;
    }
}