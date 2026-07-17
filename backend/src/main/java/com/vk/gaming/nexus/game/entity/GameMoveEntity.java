package com.vk.gaming.nexus.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "game_moves", indexes = {
        @Index(name = "idx_gamemove_room", columnList = "roomId")
}, uniqueConstraints = {
        @UniqueConstraint(columnNames = {"roomId", "boardPosition"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameMoveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "player_username", nullable = false)
    private String playerUsername;

    @Column(name = "board_position", nullable = false)
    private int boardPosition;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "create_date", nullable = false, updatable = false)
    private LocalDateTime createDate;

    @PrePersist
    protected void onCreate() {
        if (createDate == null) {
            createDate = LocalDateTime.now();
        }
    }
}
