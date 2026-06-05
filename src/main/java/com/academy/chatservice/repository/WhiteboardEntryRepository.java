package com.academy.chatservice.repository;

import com.academy.chatservice.model.WhiteboardEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WhiteboardEntryRepository extends JpaRepository<WhiteboardEntry, Long> {

    List<WhiteboardEntry> findByWhiteboard_IdOrderByOrderIndexAsc(Long whiteboardId);

    List<WhiteboardEntry> findByConversationIdOrderByOrderIndexAsc(Long conversationId);

    void deleteByWhiteboard_Id(Long whiteboardId);

    @Query("SELECT COALESCE(MAX(e.orderIndex), -1) FROM WhiteboardEntry e WHERE e.whiteboard.id = :whiteboardId")
    int findMaxOrderIndexByWhiteboardId(@Param("whiteboardId") Long whiteboardId);
}
