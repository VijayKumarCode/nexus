package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.GameMove;
import com.vk.gaming.nexus.dto.GameSystemMessage;
import com.vk.gaming.nexus.dto.TossRequest;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.model.GameMoveEntity;
import com.vk.gaming.nexus.repository.GameMoveRepository;
import com.vk.gaming.nexus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameMoveRepository gameMoveRepository;
    private final UserRepository userRepository;
    private final UserService userService; // 🔥 NEW

    private final Map<String, String[]> roomPlayersMap = new ConcurrentHashMap<>();
    private final Map<String, String> currentTurnMap = new ConcurrentHashMap<>();
    private final Map<String, String> playerXMap = new ConcurrentHashMap<>();
    private final Map<String, String> playerOMap = new ConcurrentHashMap<>();
    private final Map<String, String> tossWinnerMap = new ConcurrentHashMap<>(); // 🔥 NEW

    // ========================= GAME MOVE =========================
    @Transactional
    public GameMove processGameMove(GameMove incomingMove) {
        String rId = incomingMove.getRoomId();

        if (!currentTurnMap.containsKey(rId)) {
            recoverGameStateFromDB(rId);
        }

        // 🚨 TURN VALIDATION
        if (!incomingMove.getPlayerUsername().equals(currentTurnMap.get(rId))) {
            log.warn("Invalid turn by {}", incomingMove.getPlayerUsername());
            return null;
        }

        // 🚨 DUPLICATE MOVE CHECK
        if (gameMoveRepository.existsByRoomIdAndBoardPosition(
                rId, incomingMove.getBoardPosition())) {
            log.warn("Duplicate move at {}", incomingMove.getBoardPosition());
            return null;
        }

        // ✅ Derive symbol from server-side maps — never trust frontend
        String symbol = incomingMove.getPlayerUsername().equals(playerXMap.get(rId)) ? "X" : "O";
        incomingMove.setSymbol(symbol); // ✅ set for return broadcast too

        GameMoveEntity entity = new GameMoveEntity();
        entity.setRoomId(rId);
        entity.setPlayerUsername(incomingMove.getPlayerUsername());
        entity.setBoardPosition(incomingMove.getBoardPosition());
        entity.setSymbol(symbol);

        gameMoveRepository.save(entity);

        // 🔥 BUILD BOARD FROM DB
        char[] board = buildBoard(rId);

        // 🔥 CHECK WIN
        String winnerSymbol = checkWinner(board);

        if (winnerSymbol != null) {

            String winner = winnerSymbol.equals("X") ? playerXMap.get(rId) : playerOMap.get(rId);
            String loser = winnerSymbol.equals("X") ? playerOMap.get(rId) : playerXMap.get(rId);
            userService.incrementWins(winner);
            userService.incrementLosses(loser);
            incomingMove.setGameState("WINNER_" + winnerSymbol);
            log.info("Game finished in room {} -> Winner: {}", rId, winner);
            currentTurnMap.remove(rId);
            return incomingMove; //

        }

        boolean isDraw = true;
        for (char c : board) {
            if (c == '-') { isDraw = false; break; }
        }
        if (isDraw) {
            incomingMove.setGameState("DRAW");
            log.info("Game drawn in room {}", rId);
            currentTurnMap.remove(rId);
            return incomingMove; // ✅ stop here too
        }

        String nextTurn = incomingMove.getSymbol().equals("X")
                ? playerOMap.get(rId)
                : playerXMap.get(rId);

        currentTurnMap.put(rId, nextTurn);

        return incomingMove;
    }

    // ========================= BOARD =========================
    private char[] buildBoard(String roomId) {
        List<GameMoveEntity> history =
                gameMoveRepository.findByRoomIdOrderByCreateDateAsc(roomId);

        char[] board = new char[9];
        Arrays.fill(board, '-');

        for (GameMoveEntity move : history) {
            board[move.getBoardPosition()] = move.getSymbol().charAt(0);
        }

        return board;
    }

    private String checkWinner(char[] b) {
        int[][] winPatterns = {
                {0,1,2},{3,4,5},{6,7,8},
                {0,3,6},{1,4,7},{2,5,8},
                {0,4,8},{2,4,6}
        };

        for (int[] p : winPatterns) {
            if (b[p[0]] != '-' &&
                    b[p[0]] == b[p[1]] &&
                    b[p[1]] == b[p[2]]) {
                return String.valueOf(b[p[0]]);
            }
        }
        return null;
    }

    // ========================= RECOVERY =========================
    private void recoverGameStateFromDB(String roomId) {
        List<GameMoveEntity> history =
                gameMoveRepository.findByRoomIdOrderByCreateDateAsc(roomId);

        if (history.isEmpty()) return;

        String p1 = history.get(0).getPlayerUsername();
        String sym = history.get(0).getSymbol();

        if ("X".equals(sym)) playerXMap.put(roomId, p1);
        else playerOMap.put(roomId, p1);

        for (GameMoveEntity m : history) {
            if (!m.getPlayerUsername().equals(p1)) {
                if ("X".equals(m.getSymbol()))
                    playerXMap.put(roomId, m.getPlayerUsername());
                else
                    playerOMap.put(roomId, m.getPlayerUsername());
                break;
            }
        }

        GameMoveEntity last = history.get(history.size() - 1);
        String next = "X".equals(last.getSymbol())
                ? playerOMap.get(roomId)
                : playerXMap.get(roomId);

        currentTurnMap.put(roomId, next);
    }

    // ========================= TOSS =========================
    public GameSystemMessage processToss(TossRequest request) {
        String roomId = request.getRoomId();

        if (roomId == null || request.getPlayerOne() == null || request.getPlayerTwo() == null) {
            throw new RuntimeException("Invalid toss request: null fields. roomId="
                    + roomId + " p1=" + request.getPlayerOne() + " p2=" + request.getPlayerTwo());
        }

        boolean toss = new Random().nextBoolean();

        String winner = toss ? request.getPlayerOne() : request.getPlayerTwo();
        String loser = toss ? request.getPlayerTwo() : request.getPlayerOne();

        tossWinnerMap.put(roomId, winner);
        roomPlayersMap.put(roomId, new String[]{request.getPlayerOne(), request.getPlayerTwo()}); // ✅

        GameSystemMessage res = new GameSystemMessage();
        res.setType("TOSS");
        res.setWinner(winner);
        res.setLoser(loser);
        res.setPayload(winner);
        res.setMessage(winner + " won the toss! Choose X or O.");

        return res;
    }

    @Transactional
    public GameSystemMessage processTossDecision(String roomId,
                                                 String ignoredWinner,
                                                 String ignoredLoser,
                                                 String choice) {

        // 🔥 REAL winner from backend
        String winner = tossWinnerMap.get(roomId);

        if (winner == null) {
            throw new RuntimeException("Toss not initialized");
        }

        // ✅ Get loser from stored players, not from empty X/O maps
        String[] players = roomPlayersMap.get(roomId);
        String loser = winner.equals(players[0]) ? players[1] : players[0];

        String firstPlayer;
        String secondPlayer;

        if ("X".equalsIgnoreCase(choice)) {
            firstPlayer = winner;
            secondPlayer = loser;
        } else {
            firstPlayer = loser;
            secondPlayer = winner;
        }

        playerXMap.put(roomId, firstPlayer);
        playerOMap.put(roomId, secondPlayer);
        currentTurnMap.put(roomId, firstPlayer);

        GameSystemMessage res = new GameSystemMessage();
        res.setType("TOSS_RESULT");
        res.setWinner(winner);
        res.setLoser(loser);
        res.setPayload(firstPlayer);
        res.setMessage(firstPlayer + " starts as X. " + secondPlayer + " is O.");

        return res;
    }

    // ========================= CLEANUP =========================
    @Transactional
    public void resetGame(String roomId) {
        gameMoveRepository.deleteByRoomId(roomId);
        currentTurnMap.remove(roomId);
        playerXMap.remove(roomId);
        playerOMap.remove(roomId);
        tossWinnerMap.remove(roomId);
        roomPlayersMap.remove(roomId);
    }

    @Transactional
    public void markPlayersOnlineByRoom(String roomId) {

        resetGame(roomId); // 🔥 FIX MEMORY LEAK

        String[] parts = roomId.split("_");
        if (parts.length >= 2) {
            resetUser(parts[0]);
            resetUser(parts[1]);
        }
    }

    private void resetUser(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(User.UserStatus.ONLINE);
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }
}