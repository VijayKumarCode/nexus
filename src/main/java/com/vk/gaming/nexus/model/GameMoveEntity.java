/**
 * Project: Nexus_Multiplayer
 * Description: Entity representing a single move in the Tic-Tac-Toe game.
 * Link: https://github.com/your-repo/nexus (Replace with your actual repo link)
 * Time Complexity: O(1) for CRUD operations
 * Space Complexity: O(1) per record
 */
package com.vk.gaming.nexus.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "game_moves")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameMoveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Must be exactly 'roomId' for findByRoomId to work
    @Column(nullable = false)
    private String roomId;

    @Column(nullable = false)
    private String playerUsername;

    @Column(nullable = false)
    private int boardPosition;

    @Column(nullable = false)
    private String symbol;

    private String gameState;

    // Must be exactly 'createDate' for OrderByCreateDate to work
    @Column(name = "create_date", nullable = false, updatable = false)
    private LocalDateTime createDate = LocalDateTime.now();
}
