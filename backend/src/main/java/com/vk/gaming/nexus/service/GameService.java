package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.GameMove;
import com.vk.gaming.nexus.dto.GameSystemMessage;
import com.vk.gaming.nexus.dto.TossRequest;
import com.vk.gaming.nexus.dto.PlayerStatus;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.entity.GameMoveEntity;
import com.vk.gaming.nexus.enums.UserStatus;
import com.vk.gaming.nexus.repository.GameMoveRepository;
import com.vk.gaming.nexus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, String[]> roomPlayers = new ConcurrentHashMap<>();
    private final Map<String, String> currentTurn = new ConcurrentHashMap<>();
    private final Map<String, String> playerX = new ConcurrentHashMap<>();
    private final Map<String, String> playerO = new ConcurrentHashMap<>();
    private final Map<String, String> tossWinner = new ConcurrentHashMap<>();

    public boolean isRoomParticipant(String roomId, String username) {
        String[] players = roomPlayers.get(roomId);
        if (players == null) {
            return false;
        }
        return username.equals(players[0]) || username.equals(players[1]);
    }

    @Transactional
    public GameMove processGameMove(GameMove incomingMove) {
        String roomId = incomingMove.getRoomId();

        if (roomId == null || roomId.isBlank()) {
            log.error("processGameMove — null/blank roomId, move rejected");
            return null;
        }

        log.info("processGameMove — room={} player={} pos={}",
                roomId, incomingMove.getPlayerUsername(), incomingMove.getBoardPosition());

        if (!currentTurn.containsKey(roomId)) {
            log.warn("currentTurn miss for room={} — attempting DB recovery", roomId);
            recoverGameStateFromDB(roomId);
        }

        String expectedPlayer = currentTurn.get(roomId);
        if (expectedPlayer == null) {
            log.error("currentTurn null after recovery for room={} — move rejected", roomId);
            return null;
        }

        if (!incomingMove.getPlayerUsername().equals(expectedPlayer)) {
            log.warn("Wrong turn — room={} expected={} got={}",
                    roomId, expectedPlayer, incomingMove.getPlayerUsername());
            return null;
        }

        if (gameMoveRepository.existsByRoomIdAndBoardPosition(roomId, incomingMove.getBoardPosition())) {
            log.warn("Duplicate move pos={} room={}", incomingMove.getBoardPosition(), roomId);
            return null;
        }

        String symbol = incomingMove.getPlayerUsername().equals(playerX.get(roomId)) ? "X" : "O";
        incomingMove.setSymbol(symbol);

        log.info("Move accepted — room={} player={} pos={} symbol={}",
                roomId, incomingMove.getPlayerUsername(), incomingMove.getBoardPosition(), symbol);

        GameMoveEntity entity = new GameMoveEntity();
        entity.setRoomId(roomId);
        entity.setPlayerUsername(incomingMove.getPlayerUsername());
        entity.setBoardPosition(incomingMove.getBoardPosition());
        entity.setSymbol(symbol);

        try {
            gameMoveRepository.save(entity);
        } catch (Exception e) {
            log.error("DB save failed — room={} error={}", roomId, e.getMessage(), e);
            return null;
        }

        char[] board = buildBoard(roomId);
        String winnerSymbol = checkWinner(board);

        if (winnerSymbol != null) {
            String winner = "X".equals(winnerSymbol) ? playerX.get(roomId) : playerO.get(roomId);
            String loser = "X".equals(winnerSymbol) ? playerO.get(roomId) : playerX.get(roomId);
            userService.incrementWins(winner);
            userService.incrementLosses(loser);
            incomingMove.setGameState("WINNER_" + winnerSymbol);
            log.info("Game over — room={} winner={}", roomId, winner);
            currentTurn.remove(roomId);
            return incomingMove;
        }

        if (isBoardFull(board)) {
            incomingMove.setGameState("DRAW");
            log.info("Draw — room={}", roomId);
            currentTurn.remove(roomId);
            return incomingMove;
        }

        String nextPlayer = "X".equals(symbol) ? playerO.get(roomId) : playerX.get(roomId);
        currentTurn.put(roomId, nextPlayer);
        incomingMove.setGameState("ONGOING");
        log.info("Next turn — room={} nextPlayer={}", roomId, nextPlayer);
        return incomingMove;
    }

    private char[] buildBoard(String roomId) {
        List<GameMoveEntity> history = gameMoveRepository.findByRoomIdOrderByCreateDateAsc(roomId);
        char[] board = new char[9];
        Arrays.fill(board, '-');
        for (GameMoveEntity move : history) {
            board[move.getBoardPosition()] = move.getSymbol().charAt(0);
        }
        return board;
    }

    private String checkWinner(char[] b) {
        int[][] patterns = {
                {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
                {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
                {0, 4, 8}, {2, 4, 6}
        };
        for (int[] p : patterns) {
            if (b[p[0]] != '-' && b[p[0]] == b[p[1]] && b[p[1]] == b[p[2]]) {
                return String.valueOf(b[p[0]]);
            }
        }
        return null;
    }

    private boolean isBoardFull(char[] board) {
        for (char c : board) {
            if (c == '-') return false;
        }
        return true;
    }

    private void recoverGameStateFromDB(String roomId) {
        List<GameMoveEntity> history = gameMoveRepository.findByRoomIdOrderByCreateDateAsc(roomId);

        if (history.isEmpty()) {
            log.warn("Recovery failed — no DB history for room={}", roomId);
            return;
        }

        for (GameMoveEntity m : history) {
            if ("X".equals(m.getSymbol()) && !playerX.containsKey(roomId))
                playerX.put(roomId, m.getPlayerUsername());
            else if ("O".equals(m.getSymbol()) && !playerO.containsKey(roomId))
                playerO.put(roomId, m.getPlayerUsername());
            if (playerX.containsKey(roomId) && playerO.containsKey(roomId)) break;
        }

        String px = playerX.get(roomId);
        String po = playerO.get(roomId);
        if (px != null && po != null)
            roomPlayers.put(roomId, new String[]{px, po});

        GameMoveEntity last = history.get(history.size() - 1);
        if ("X".equals(last.getSymbol()) && po != null) {
            currentTurn.put(roomId, po);
            log.info("Recovery OK — room={} X={} O={} next={}", roomId, px, po, po);
        } else if ("O".equals(last.getSymbol()) && px != null) {
            currentTurn.put(roomId, px);
            log.info("Recovery OK — room={} X={} O={} next={}", roomId, px, po, px);
        } else {
            log.warn("Recovery partial — room={} X={} O=null", roomId, px);
        }
    }

    public GameSystemMessage processToss(TossRequest request) {
        String roomId = request.getRoomId();

        if (roomId == null || request.getPlayerOne() == null || request.getPlayerTwo() == null) {
            throw new RuntimeException("Invalid toss — null fields: roomId="
                    + roomId + " p1=" + request.getPlayerOne() + " p2=" + request.getPlayerTwo());
        }

        boolean coin = new Random().nextBoolean();
        String winner = coin ? request.getPlayerOne() : request.getPlayerTwo();
        String loser = coin ? request.getPlayerTwo() : request.getPlayerOne();

        tossWinner.put(roomId, winner);
        roomPlayers.put(roomId, new String[]{request.getPlayerOne(), request.getPlayerTwo()});
        log.info("Toss — room={} winner={}", roomId, winner);

        GameSystemMessage res = new GameSystemMessage();
        res.setType("TOSS");
        res.setWinner(winner);
        res.setLoser(loser);
        res.setPayload(winner);
        res.setMessage(winner + " won the toss! Choose X or O.");
        return res;
    }

    public GameSystemMessage processTossDecision(String roomId, String choice) {
        String winner = tossWinner.get(roomId);
        if (winner == null)
            throw new RuntimeException("Toss not initialized for room=" + roomId);

        String[] players = roomPlayers.get(roomId);
        if (players == null)
            throw new RuntimeException("roomPlayers missing for room=" + roomId);

        String loser = winner.equals(players[0]) ? players[1] : players[0];
        String firstPlayer = "X".equalsIgnoreCase(choice) ? winner : loser;
        String secondPlayer = "X".equalsIgnoreCase(choice) ? loser : winner;

        playerX.put(roomId, firstPlayer);
        playerO.put(roomId, secondPlayer);
        currentTurn.put(roomId, firstPlayer);

        log.info("Toss decision — room={} choice={} X={} O={}", roomId, choice, firstPlayer, secondPlayer);

        GameSystemMessage res = new GameSystemMessage();
        res.setType("TOSS_RESULT");
        res.setWinner(winner);
        res.setLoser(loser);
        res.setPayload(firstPlayer);
        res.setMessage(firstPlayer + " starts as X. " + secondPlayer + " is O.");
        return res;
    }

    @Transactional
    public void resetGame(String roomId) {
        gameMoveRepository.deleteByRoomId(roomId);
        currentTurn.remove(roomId);
        playerX.remove(roomId);
        playerO.remove(roomId);
        tossWinner.remove(roomId);
        roomPlayers.remove(roomId);
        log.info("Game reset — room={}", roomId);
    }

    @Transactional
    public void markPlayersOnlineByRoom(String roomId) {
        resetGame(roomId);
        String[] parts = roomId.split("_");
        if (parts.length >= 2) {
            resetUser(parts[0]);
            resetUser(parts[1]);
        }
    }

    private void resetUser(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(UserStatus.ONLINE);
            u.setLastSeen(System.currentTimeMillis());
            userRepository.save(u);
        });
    }

    @Transactional
    public void handlePlayerDisconnect(String username) {
        log.info("Handling game disconnection for user: {}", username);
        List<String> roomsToCleanup = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : roomPlayers.entrySet()) {
            String roomId = entry.getKey();
            String[] players = entry.getValue();
            if (players != null && (username.equals(players[0]) || username.equals(players[1]))) {
                roomsToCleanup.add(roomId);
            }
        }

        for (String roomId : roomsToCleanup) {
            log.info("Aborting game in room {} due to user disconnect: {}", roomId, username);
            String[] players = roomPlayers.get(roomId);
            if (players != null) {
                String opponent = username.equals(players[0]) ? players[1] : players[0];

                GameSystemMessage abortMsg = new GameSystemMessage();
                abortMsg.setType("GAME_ABORTED");
                abortMsg.setWinner(opponent);
                abortMsg.setLoser(username);
                abortMsg.setPayload(username);
                abortMsg.setMessage(username + " disconnected. You win!");

                try {
                    messagingTemplate.convertAndSend("/topic/game/" + roomId, abortMsg);
                } catch (Exception e) {
                    log.error("Failed to send disconnect abort message to room {}: {}", roomId, e.getMessage());
                }

                resetUser(opponent);
                try {
                    messagingTemplate.convertAndSend("/topic/lobby.status", new PlayerStatus(opponent, UserStatus.ONLINE));
                } catch (Exception e) {
                    log.error("Failed to send lobby status update for opponent {}: {}", opponent, e.getMessage());
                }
            }
            resetGame(roomId);
        }
    }
}
