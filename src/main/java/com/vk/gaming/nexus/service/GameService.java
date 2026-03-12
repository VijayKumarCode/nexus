/**
 * Project: Nexus Multiplayer
 * Description: Real-time game logic service handling moves, win detection, and toss mechanics.
 * Time Complexity: O(1) for move processing (fixed win-pattern check)
 * Space Complexity: O(1) (fixed 3x3 grid size)
 */
package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.GameMove;
import com.vk.gaming.nexus.dto.GameSystemMessage;
import com.vk.gaming.nexus.dto.TossRequest;
import com.vk.gaming.nexus.model.GameMoveEntity;
import com.vk.gaming.nexus.repository.GameMoveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameMoveRepository gameMoveRepository;

    @Transactional
    public GameMove processGameMove(GameMove incomingMove) {
        String rId = incomingMove.getRoomId();

        // 1. FIXED: Use the room-aware existence check
        if (gameMoveRepository.existsByRoomIdAndBoardPosition(rId, incomingMove.getBoardPosition())) {
            log.warn("Position occupied in room {}", rId);
            return null;
        }

        // 2. FIXED: Use the room-aware top move check
        Optional<GameMoveEntity> lastMove = gameMoveRepository.findTopByRoomIdOrderByCreateDateDesc(rId);

        // Validate turn logic...
        if (lastMove.isPresent() && lastMove.get().getPlayerUsername().equals(incomingMove.getPlayerUsername())) {
            return null;
        }

        // 3. Logic to determine symbol...
        String symbol = (lastMove.isPresent() && "X".equals(lastMove.get().getSymbol())) ? "O" : "X";

        GameMoveEntity entity = new GameMoveEntity();
        entity.setRoomId(rId);
        entity.setBoardPosition(incomingMove.getBoardPosition());
        entity.setPlayerUsername(incomingMove.getPlayerUsername());
        entity.setSymbol(symbol);

        // 4. Save and check state...
        GameMoveEntity saved = gameMoveRepository.save(entity);
        saved.setGameState(checkGameState(rId)); // Ensure checkGameState is also room-aware

        return mapToDto(gameMoveRepository.save(saved));
    }

    private String checkGameState(String rId) {
        List<GameMoveEntity> allMoves = gameMoveRepository.findAll();
        String[] board = new String[9];
        for (GameMoveEntity m : allMoves) {
            board[m.getBoardPosition()] = m.getSymbol();
        }

        int[][] winPatterns = {
                {0,1,2}, {3,4,5}, {6,7,8}, // Rows
                {0,3,6}, {1,4,7}, {2,5,8}, // Cols
                {0,4,8}, {2,4,6}           // Diagonals
        };

        for (int[] p : winPatterns) {
            if (board[p[0]] != null && board[p[0]].equals(board[p[1]]) && board[p[0]].equals(board[p[2]])) {
                return "WINNER_" + board[p[0]];
            }
        }

        return allMoves.size() == 9 ? "DRAW" : "ONGOING";
    }

    @Transactional
    public void resetGame() {
        log.info("Resetting game: Clearing all moves from the database.");
        gameMoveRepository.deleteAll();
    }

    public GameSystemMessage processToss(TossRequest request) {
        String tossWinner = Math.random() < 0.5 ? request.getPlayerOne() : request.getPlayerTwo();
        log.info("Toss winner determined: {}", tossWinner);

        GameSystemMessage response = new GameSystemMessage();
        response.setType("TOSS_RESULT");
        response.setPayload(tossWinner);
        response.setMessage(String.format("Toss winner is %s. You may start as 'X'.", tossWinner));
        return response;
    }

    private GameMove mapToDto(GameMoveEntity entity) {
        GameMove dto = new GameMove();
        dto.setBoardPosition(entity.getBoardPosition());
        dto.setPlayerUsername(entity.getPlayerUsername());
        dto.setSymbol(entity.getSymbol());
        dto.setGameState(entity.getGameState());
        return dto;
    }

    public GameSystemMessage processPass(String passingPlayer) {
        if (!StringUtils.hasText(passingPlayer)) {
            log.warn("ProcessPass called with an invalid or empty player name.");
            return null;
        }

        log.info("Player {} has elected to pass their starting turn.", passingPlayer);

        GameSystemMessage response = new GameSystemMessage();
        response.setType("TURN_PASSED");
        response.setPayload(passingPlayer);
        response.setMessage(String.format("%s has passed. The opponent will now start the game as 'X'.", passingPlayer));

        return response;
    }
}