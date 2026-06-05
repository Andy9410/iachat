package com.academy.chatservice.service;

import com.academy.chatservice.model.ReasoningNode;
import com.academy.chatservice.model.ReasoningNodeDto;
import com.academy.chatservice.repository.ConversationRepository;
import com.academy.chatservice.repository.ReasoningNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ReasoningNodeService {

    private static final Logger log = LoggerFactory.getLogger(ReasoningNodeService.class);

    private final ReasoningNodeRepository nodeRepository;
    private final ConversationRepository conversationRepository;

    public ReasoningNodeService(ReasoningNodeRepository nodeRepository,
                                ConversationRepository conversationRepository) {
        this.nodeRepository = nodeRepository;
        this.conversationRepository = conversationRepository;
    }

    @Transactional
    public ReasoningNodeDto create(Long conversationId, Long whiteboardId, Long parentNodeId,
                                   String nodeType, String title, String description,
                                   String status, Integer orderIndex, String userEmail) {
        requireConversation(conversationId, userEmail);

        int level = 0;
        if (parentNodeId != null) {
            ReasoningNode parent = nodeRepository.findById(parentNodeId)
                    .filter(n -> n.getConversationId().equals(conversationId))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Nodo padre no encontrado en esta conversación"));
            level = parent.getLevel() + 1;
        }

        int resolvedOrderIndex = orderIndex != null && orderIndex >= 0 ? orderIndex :
                (parentNodeId == null
                        ? nodeRepository.findMaxRootOrderIndex(conversationId) + 1
                        : nodeRepository.findMaxChildOrderIndex(parentNodeId) + 1);

        ReasoningNode node = new ReasoningNode();
        node.setConversationId(conversationId);
        node.setWhiteboardId(whiteboardId);
        node.setParentNodeId(parentNodeId);
        node.setNodeType(normalizeNodeType(nodeType));
        node.setTitle(title != null ? title.trim() : "Sin título");
        node.setDescription(description);
        node.setStatus(normalizeStatus(status));
        node.setLevel(level);
        node.setOrderIndex(resolvedOrderIndex);

        ReasoningNode saved = nodeRepository.save(node);
        log.info("[REASONING] conversation={} node={} type={} level={} status={}",
                conversationId, saved.getId(), saved.getNodeType(), level, saved.getStatus());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ReasoningNodeDto> getTree(Long conversationId, String userEmail) {
        requireConversation(conversationId, userEmail);
        return nodeRepository.findByConversationIdOrderByLevelAscOrderIndexAsc(conversationId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public ReasoningNodeDto updateStatus(Long nodeId, Long conversationId, String status, String userEmail) {
        requireConversation(conversationId, userEmail);
        ReasoningNode node = nodeRepository.findByIdAndConversationId(nodeId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nodo no encontrado"));
        node.setStatus(normalizeStatus(status));
        return toDto(nodeRepository.save(node));
    }

    /** Builds LLM context from the reasoning graph of a conversation. */
    public String buildReasoningContext(Long conversationId) {
        var nodes = nodeRepository.findByConversationIdOrderByLevelAscOrderIndexAsc(conversationId);
        if (nodes.isEmpty()) return "";

        var sb = new StringBuilder("\nReasoning Graph actual:\n\n");
        for (var node : nodes) {
            String indent = "  ".repeat(node.getLevel());
            sb.append(indent)
              .append(node.getNodeType())
              .append(" (").append(node.getStatus()).append("): ")
              .append(node.getTitle());
            if (node.getDescription() != null && !node.getDescription().isBlank()) {
                sb.append("\n").append(indent).append("  → ").append(node.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String normalizeNodeType(String type) {
        if (type == null) return "SUBPROBLEM";
        return switch (type.toUpperCase()) {
            case "PROBLEM", "PLAN", "DECOMPOSITION", "SUBPROBLEM", "SUBPROBLEM_SOLUTION",
                 "PARTIAL_RESULT", "FINAL_INTEGRATION", "FINAL_ANSWER", "USER_QUESTION" -> type.toUpperCase();
            default -> "SUBPROBLEM";
        };
    }

    private String normalizeStatus(String status) {
        if (status == null) return "PENDING";
        return switch (status.toUpperCase()) {
            case "PENDING", "IN_PROGRESS", "COMPLETED", "FAILED" -> status.toUpperCase();
            default -> "PENDING";
        };
    }

    private ReasoningNodeDto toDto(ReasoningNode n) {
        return new ReasoningNodeDto(
                n.getId(), n.getConversationId(), n.getWhiteboardId(),
                n.getParentNodeId(), n.getNodeType(), n.getTitle(),
                n.getDescription(), n.getStatus(), n.getLevel(), n.getOrderIndex()
        );
    }

    private void requireConversation(Long conversationId, String userEmail) {
        conversationRepository.findByIdAndUserEmail(conversationId, userEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversación no encontrada"));
    }
}
