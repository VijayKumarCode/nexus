package com.vk.gaming.nexus.repository;

import com.vk.gaming.nexus.model.GameMoveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameMoveRepository extends JpaRepository<GameMoveEntity, Long> {

    List<GameMoveEntity> findByRoomIdOrderByCreateDateAsc(String roomId);

    @Modifying
    @Transactional
    void deleteByRoomId(String roomId);

    Optional<GameMoveEntity> findTopByRoomIdOrderByCreateDateDesc(String roomId);
    boolean existsByRoomIdAndBoardPosition(String roomId, int boardPosition);
}