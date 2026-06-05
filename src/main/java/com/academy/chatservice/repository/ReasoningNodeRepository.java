package com.academy.chatservice.repository;

import com.academy.chatservice.model.ReasoningNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReasoningNodeRepository extends JpaRepository<ReasoningNode, Long> {

    List<ReasoningNode> findByConversationIdOrderByLevelAscOrderIndexAsc(Long conversationId);

    List<ReasoningNode> findByConversationIdAndWhiteboardIdOrderByLevelAscOrderIndexAsc(
            Long conversationId, Long whiteboardId);

    @Query("SELECT COALESCE(MAX(n.orderIndex), -1) FROM ReasoningNode n " +
           "WHERE n.conversationId = :convId AND n.parentNodeId IS NULL")
    int findMaxRootOrderIndex(@Param("convId") Long conversationId);

    @Query("SELECT COALESCE(MAX(n.orderIndex), -1) FROM ReasoningNode n " +
           "WHERE n.parentNodeId = :parentId")
    int findMaxChildOrderIndex(@Param("parentId") Long parentNodeId);

    Optional<ReasoningNode> findByIdAndConversationId(Long id, Long conversationId);
}
