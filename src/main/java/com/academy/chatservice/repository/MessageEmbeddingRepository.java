package com.academy.chatservice.repository;

import com.academy.chatservice.model.MessageEmbedding;
import com.academy.chatservice.model.SimilarMessageProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageEmbeddingRepository extends JpaRepository<MessageEmbedding, Long> {

    @Modifying
    @Query(value = "INSERT INTO message_embeddings (message_id, embedding, created_at) VALUES (:messageId, CAST(:embedding AS vector), NOW())", nativeQuery = true)
    void insertEmbedding(@Param("messageId") Long messageId, @Param("embedding") String embedding);

    @Query(value = """
            SELECT m.content AS content, m.role AS role
            FROM message_embeddings me
            JOIN messages m ON m.id = me.message_id
            JOIN conversations c ON c.id = m.conversation_id
            WHERE c.user_email = :userEmail
              AND m.conversation_id != :excludeConversationId
            ORDER BY me.embedding <=> CAST(:embedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<SimilarMessageProjection> findSimilar(
            @Param("embedding") String embedding,
            @Param("userEmail") String userEmail,
            @Param("excludeConversationId") Long excludeConversationId,
            @Param("topK") int topK);
}
