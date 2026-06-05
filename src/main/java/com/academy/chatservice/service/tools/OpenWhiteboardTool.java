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
                "Abre una pizarra de enseñanza asociada a la conversación. "
                + "Usá esta tool cuando el estudiante pida una explicación paso a paso, "
                + "una resolución visual o diga 'explicame en la pizarra'.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "conversationId", Map.of("type", "integer", "description", "ID de la conversación activa"),
                                "title", Map.of("type", "string", "description", "Título descriptivo de la pizarra, ej: 'Resolución paso a paso'"),
                                "mode", Map.of("type", "string", "enum", List.of("teaching", "default"), "description", "Modo de la pizarra")
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
                args.title() != null ? args.title() : "Pizarra de enseñanza",
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
