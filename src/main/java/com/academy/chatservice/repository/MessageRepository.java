package com.academy.chatservice.repository;

import com.academy.chatservice.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    long countByConversationId(Long conversationId);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :convId ORDER BY m.createdAt DESC LIMIT :limit")
    List<Message> findLastN(@Param("convId") Long conversationId, @Param("limit") int limit);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :convId ORDER BY m.createdAt ASC LIMIT :limit")
    List<Message> findFirstN(@Param("convId") Long conversationId, @Param("limit") int limit);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :convId AND m.id < :beforeId ORDER BY m.id DESC LIMIT :limit")
    List<Message> findBeforeId(@Param("convId") Long conversationId, @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
