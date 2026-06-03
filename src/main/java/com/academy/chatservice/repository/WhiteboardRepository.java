package com.academy.chatservice.repository;

import com.academy.chatservice.model.Whiteboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhiteboardRepository extends JpaRepository<Whiteboard, Long> {
    List<Whiteboard> findByConversationIdOrderByUpdatedAtDesc(Long conversationId);

    Optional<Whiteboard> findFirstByConversationIdOrderByUpdatedAtDesc(Long conversationId);

    Optional<Whiteboard> findFirstByConversationIdAndExerciseLabelIgnoreCaseOrderByUpdatedAtDesc(
            Long conversationId,
            String exerciseLabel
    );
}
