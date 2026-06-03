package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.model.tools.WhiteboardIdArgs;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SummarizeWhiteboardTool implements ChatTool<WhiteboardIdArgs> {
    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public SummarizeWhiteboardTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    public ToolDefinition definition() {
        return new ToolDefinition(
                "summarize_whiteboard",
                "Convierte la pizarra visual en una representación textual. Solo lectura.",
                Map.of(
                        "type", "object",
                        "properties", Map.of("whiteboardId", Map.of("type", "string")),
                        "required", List.of("whiteboardId")
                )
        );
    }

    public Class<WhiteboardIdArgs> argumentType() { return WhiteboardIdArgs.class; }

    public Object execute(WhiteboardIdArgs args) {
        return whiteboardService.summarize(args.whiteboardId(), context.require().userEmail());
    }
}
