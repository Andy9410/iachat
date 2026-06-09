package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.WhiteboardAction;
import com.academy.chatservice.model.tools.OpenWhiteboardArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenWhiteboardTool implements ChatTool<OpenWhiteboardArgs> {

    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public OpenWhiteboardTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "open_whiteboard",
                "Crea el workspace de Resolución guiada de la conversación (si todavía no existe). "
                + "Usalo solo cuando aún no hay workspace; si ya existe en el contexto, reutilizalo con "
                + "inject_whiteboard_content en lugar de abrir otro. El estudiante no necesita pedirlo: "
                + "decidilo según la intención educativa (no entiende algo, pide ver cómo se resuelve, etc.).",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "conversationId", Map.of("type", "integer", "description", "ID de la conversación activa"),
                                "title", Map.of("type", "string", "description", "Intención educativa del workspace, ej: 'Resolver el ejercicio 2 paso a paso'"),
                                "mode", Map.of("type", "string", "enum", List.of("teaching", "default"), "description", "Modo del workspace")
                        ),
                        "required", List.of("conversationId", "title")
                )
        );
    }

    @Override
    public Class<OpenWhiteboardArgs> argumentType() { return OpenWhiteboardArgs.class; }

    @Override
    public Object execute(OpenWhiteboardArgs args) {
        var ctx = context.require();
        var dto = whiteboardService.openForTeaching(
                ctx.conversationId(), // always use the real context conversationId, never trust LLM args
                args.title() != null ? args.title() : "Resolución guiada",
                args.mode() != null ? args.mode() : "teaching",
                ctx.userEmail()
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", dto.conversationId());
        payload.put("whiteboardId", dto.id());
        payload.put("title", dto.title());
        payload.put("mode", dto.mode());

        return new WhiteboardAction("OPEN_WHITEBOARD", payload);
    }
}
