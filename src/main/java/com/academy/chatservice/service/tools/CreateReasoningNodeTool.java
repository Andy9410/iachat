package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.ReasoningNodeDto;
import com.academy.chatservice.model.WhiteboardAction;
import com.academy.chatservice.model.tools.CreateReasoningNodeArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.ReasoningNodeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CreateReasoningNodeTool implements ChatTool<CreateReasoningNodeArgs> {

    private final ReasoningNodeService reasoningNodeService;
    private final ToolExecutionContext context;

    public CreateReasoningNodeTool(ReasoningNodeService reasoningNodeService, ToolExecutionContext context) {
        this.reasoningNodeService = reasoningNodeService;
        this.context = context;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "create_reasoning_node",
                "Crea y persiste un nodo en el árbol de razonamiento (Reasoning Graph) de la conversación. "
                + "Usá esta tool ANTES de resolver un problema complejo para estructurar el razonamiento. "
                + "Secuencia recomendada: PROBLEM → PLAN → SUBPROBLEM (uno por parte) → FINAL_ANSWER. "
                + "El árbol queda persistido y entra como contexto en los próximos mensajes.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "conversationId", Map.of("type", "integer",
                                        "description", "ID de la conversación activa"),
                                "whiteboardId", Map.of("type", "string",
                                        "description", "ID de la pizarra activa (opcional)"),
                                "parentNodeId", Map.of("type", "integer",
                                        "description", "ID del nodo padre. Null si es raíz del árbol."),
                                "nodeType", Map.of("type", "string",
                                        "enum", List.of("PROBLEM", "PLAN", "DECOMPOSITION",
                                                "SUBPROBLEM", "SUBPROBLEM_SOLUTION", "PARTIAL_RESULT",
                                                "FINAL_INTEGRATION", "FINAL_ANSWER", "USER_QUESTION"),
                                        "description", "Tipo del nodo en el árbol de razonamiento"),
                                "title", Map.of("type", "string",
                                        "description", "Título conciso del nodo"),
                                "description", Map.of("type", "string",
                                        "description", "Descripción detallada o razonamiento del nodo"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED", "FAILED"),
                                        "description", "Estado actual del nodo"),
                                "orderIndex", Map.of("type", "integer",
                                        "description", "Posición dentro del mismo nivel (0-based). Si se omite, se calcula automáticamente.")
                        ),
                        "required", List.of("conversationId", "nodeType", "title")
                )
        );
    }

    @Override
    public Class<CreateReasoningNodeArgs> argumentType() { return CreateReasoningNodeArgs.class; }

    @Override
    public Object execute(CreateReasoningNodeArgs args) {
        var ctx = context.require();
        Long conversationId = ctx.conversationId(); // always use real context, never trust LLM args

        // whiteboardId may come as string in some LLM implementations
        Long whiteboardId = args.whiteboardId() != null ? parseWhiteboardId(args.whiteboardId()) : null;

        ReasoningNodeDto saved = reasoningNodeService.create(
                conversationId,
                whiteboardId,
                args.parentNodeId(),
                args.nodeType(),
                args.title(),
                args.description(),
                args.status(),
                args.orderIndex(),
                ctx.userEmail()
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", saved.nodeId());
        payload.put("conversationId", saved.conversationId());
        payload.put("whiteboardId", saved.whiteboardId());
        payload.put("parentNodeId", saved.parentNodeId());
        payload.put("nodeType", saved.nodeType());
        payload.put("title", saved.title());
        payload.put("description", saved.description());
        payload.put("status", saved.status());
        payload.put("level", saved.level());
        payload.put("orderIndex", saved.orderIndex());

        return new WhiteboardAction("CREATE_REASONING_NODE", payload);
    }

    private Long parseWhiteboardId(Long id) {
        return id;
    }
}
