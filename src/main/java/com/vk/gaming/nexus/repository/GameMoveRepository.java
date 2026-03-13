package com.vk.gaming.nexus.repository;


import com.vk.gaming.nexus.model.GameMoveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameMoveRepository extends JpaRepository<GameMoveEntity, Long> {

    List<GameMoveEntity> findByRoomIdOrderByCreateDateAsc(String roomId);

    // ADDED: For fetching moves strictly isolated to a specific room
    List<GameMoveEntity> findByRoomId(String roomId);

    // ADDED: For resetting ONLY the specific room's board
    void deleteByRoomId(String roomId);

    // Existing methods
    Optional<GameMoveEntity> findTopByRoomIdOrderByCreateDateDesc(String roomId);
    boolean existsByRoomIdAndBoardPosition(String roomId, int boardPosition);
}
