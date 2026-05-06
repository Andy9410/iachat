package com.academy.chatservice.repository;

import com.academy.chatservice.model.MessageEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageEmbeddingRepository extends JpaRepository<MessageEmbedding, Long> {

    @Modifying
    @Query(value = "INSERT INTO message_embeddings (message_id, embedding, created_at) VALUES (:messageId, CAST(:embedding AS vector), NOW())", nativeQuery = true)
    void insertEmbedding(@Param("messageId") Long messageId, @Param("embedding") String embedding);
}
