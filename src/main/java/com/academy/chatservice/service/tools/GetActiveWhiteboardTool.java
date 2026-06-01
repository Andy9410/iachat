package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.GetActiveWhiteboardArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GetActiveWhiteboardTool implements ChatTool<GetActiveWhiteboardArgs> {
    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public GetActiveWhiteboardTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    public ToolDefinition definition() {
        return new ToolDefinition(
                "get_active_whiteboard",
                "Obtiene la pizarra activa de la conversación actual. Solo lectura.",
                Map.of(
                        "type", "object",
                        "properties", Map.of("conversationId", Map.of("type", "number")),
                        "required", List.of("conversationId")
                )
        );
    }

    public Class<GetActiveWhiteboardArgs> argumentType() { return GetActiveWhiteboardArgs.class; }

    public Object execute(GetActiveWhiteboardArgs args) {
        var ctx = context.require();
        Long conversationId = args.conversationId() != null ? args.conversationId() : ctx.conversationId();
        return whiteboardService.active(conversationId, ctx.userEmail());
    }
}
