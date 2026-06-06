package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.InjectWhiteboardRequest;
import com.academy.chatservice.model.WhiteboardAction;
import com.academy.chatservice.model.WhiteboardEntryDto;
import com.academy.chatservice.model.tools.InjectWhiteboardArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InjectWhiteboardContentTool implements ChatTool<InjectWhiteboardArgs> {

    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public InjectWhiteboardContentTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "inject_whiteboard_content",
                "Inyecta bloques de contenido estructurado en la pizarra activa. "
                + "Usá esta tool para escribir explicaciones paso a paso, fórmulas, ejemplos y notas. "
                + "El contenido queda persistido y disponible como contexto en los próximos mensajes. "
                + "Los bloques se anexan automáticamente respetando el orden existente.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "conversationId", Map.of("type", "integer", "description", "ID de la conversación activa"),
                                "whiteboardId", Map.of("type", "string", "description", "ID de la pizarra activa"),
                                "blocks", Map.of(
                                        "type", "array",
                                        "description", "Bloques de contenido a inyectar. Cada bloque es una unidad de información.",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "type", Map.of(
                                                                "type", "string",
                                                                "enum", List.of("TITLE", "TEXT", "STEP", "FORMULA",
                                                                        "EXAMPLE", "WARNING", "QUESTION",
                                                                        "DRAWING_INSTRUCTION", "SYSTEM_NOTE"),
                                                                "description", "Tipo del bloque"),
                                                        "content", Map.of("type", "string", "description", "Contenido del bloque"),
                                                        "orderIndex", Map.of("type", "integer",
                                                                "description", "Posición relativa dentro del conjunto de bloques que estás enviando ahora (1, 2, 3...)")
                                                ),
                                                "required", List.of("type", "content", "orderIndex")
                                        )
                                )
                        ),
                        "required", List.of("conversationId", "whiteboardId", "blocks")
                )
        );
    }

    @Override
    public Class<InjectWhiteboardArgs> argumentType() { return InjectWhiteboardArgs.class; }

    @Override
    public Object execute(InjectWhiteboardArgs args) {
        var ctx = context.require();
        Long conversationId = ctx.conversationId(); // always use real context, never trust LLM args

        // Convert tool args to InjectWhiteboardRequest.BlockRequest
        List<InjectWhiteboardRequest.BlockRequest> blocks = args.blocks() == null ? List.of() :
                args.blocks().stream()
                        .map(b -> new InjectWhiteboardRequest.BlockRequest(
                                b.type(), "assistant", b.content(), b.orderIndex(), b.metadata()))
                        .toList();

        List<WhiteboardEntryDto> saved = whiteboardService.injectBlocks(
                args.whiteboardId(), conversationId, blocks, ctx.userEmail());

        List<Map<String, Object>> blocksPayload = saved.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("content", e.content());
            m.put("orderIndex", e.orderIndex());
            if (e.metadata() != null) m.put("metadata", e.metadata());
            return m;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("whiteboardId", args.whiteboardId());
        payload.put("blocks", blocksPayload);

        return new WhiteboardAction("INJECT_WHITEBOARD_CONTENT", payload);
    }
}
