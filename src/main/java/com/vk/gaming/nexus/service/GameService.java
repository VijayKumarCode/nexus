
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

        if (gameMoveRepository.existsByRoomIdAndBoardPosition(rId, incomingMove.getBoardPosition())) {
            log.warn("Position occupied in room {}", rId);
            return null;
        }

        Optional<GameMoveEntity> lastMove = gameMoveRepository.findTopByRoomIdOrderByCreateDateDesc(rId);

        if (lastMove.isPresent() && lastMove.get().getPlayerUsername().equals(incomingMove.getPlayerUsername())) {
            return null;
        }

        String symbol = (lastMove.isPresent() && "X".equals(lastMove.get().getSymbol())) ? "O" : "X";

        GameMoveEntity entity = new GameMoveEntity();
        entity.setRoomId(rId);
        entity.setBoardPosition(incomingMove.getBoardPosition());
        entity.setPlayerUsername(incomingMove.getPlayerUsername());
        entity.setSymbol(symbol);

        GameMoveEntity saved = gameMoveRepository.save(entity);
        saved.setGameState(checkGameState(rId));

        return mapToDto(gameMoveRepository.save(saved));
    }

    private String checkGameState(String rId) {
        // FIXED: Only fetch moves for the isolated room, not the entire database
        List<GameMoveEntity> roomMoves = gameMoveRepository.findByRoomId(rId);
        String[] board = new String[9];

        for (GameMoveEntity m : roomMoves) {
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

        return roomMoves.size() == 9 ? "DRAW" : "ONGOING";
    }

    @Transactional
    public void resetGame(String roomId) {
        // FIXED: Only delete moves for the specific room ID
        log.info("Resetting game for room: {}", roomId);
        gameMoveRepository.deleteByRoomId(roomId);
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
        dto.setRoomId(entity.getRoomId()); // Ensure room ID is mapped back for the frontend
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