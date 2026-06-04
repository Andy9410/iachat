package com.academy.chatservice.repository;

import com.academy.chatservice.model.WhiteboardEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhiteboardEntryRepository extends JpaRepository<WhiteboardEntry, Long> {

    List<WhiteboardEntry> findByWhiteboard_IdOrderByOrderIndexAsc(Long whiteboardId);

    List<WhiteboardEntry> findByConversationIdOrderByOrderIndexAsc(Long conversationId);

    void deleteByWhiteboard_Id(Long whiteboardId);
}
