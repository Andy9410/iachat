package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.WhiteboardAction;
import com.academy.chatservice.model.WhiteboardEntryDto;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.model.tools.UpdateWhiteboardArgs;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class UpdateWhiteboardTool implements ChatTool<UpdateWhiteboardArgs> {

    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public UpdateWhiteboardTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "update_whiteboard",
                "Escribe contenido paso a paso en la pizarra activa. "
                + "Cada entrada es un paso, fórmula, texto o nota del sistema. "
                + "Usá esta tool después de open_whiteboard para agregar el contenido.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "whiteboardId", Map.of("type", "string", "description", "ID de la pizarra activa"),
                                "conversationId", Map.of("type", "integer", "description", "ID de la conversación"),
                                "entries", Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "type", Map.of("type", "string",
                                                                "enum", List.of("TEXT", "STEP", "FORMULA", "DRAWING", "HIGHLIGHT", "SYSTEM_NOTE")),
                                                        "content", Map.of("type", "string"),
                                                        "orderIndex", Map.of("type", "integer")
                                                ),
                                                "required", List.of("type", "content", "orderIndex")
                                        )
                                )
                        ),
                        "required", List.of("whiteboardId", "conversationId", "entries")
                )
        );
    }

    @Override
    public Class<UpdateWhiteboardArgs> argumentType() { return UpdateWhiteboardArgs.class; }

    @Override
    public Object execute(UpdateWhiteboardArgs args) {
        var ctx = context.require();
        List<WhiteboardEntryDto> saved = whiteboardService.addEntries(
                args.whiteboardId(),
                args.conversationId() != null ? args.conversationId() : ctx.conversationId(),
                args.entries(),
                ctx.userEmail()
        );

        List<Map<String, Object>> entriesPayload = saved.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("content", e.content());
            m.put("orderIndex", e.orderIndex());
            return m;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", args.conversationId());
        payload.put("whiteboardId", args.whiteboardId());
        payload.put("entries", entriesPayload);

        return new WhiteboardAction("UPDATE_WHITEBOARD", payload);
    }
}
