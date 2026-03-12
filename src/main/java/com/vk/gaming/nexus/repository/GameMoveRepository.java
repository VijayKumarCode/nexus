package com.vk.gaming.nexus.repository;


import com.vk.gaming.nexus.model.GameMoveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameMoveRepository extends JpaRepository<GameMoveEntity, Long> {

    List<GameMoveEntity> findByRoomIdOrderByCreateDateAsc(String roomId);

    // FIXED: Now finds the recent move FOR A SPECIFIC ROOM
    Optional<GameMoveEntity> findTopByRoomIdOrderByCreateDateDesc(String roomId);

    // FIXED: Now checks if a cell is occupied WITHIN A SPECIFIC ROOM
    boolean existsByRoomIdAndBoardPosition(String roomId, int boardPosition);
}
