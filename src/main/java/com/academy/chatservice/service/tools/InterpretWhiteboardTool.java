package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.InterpretWhiteboardArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.WhiteboardToolService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InterpretWhiteboardTool implements ChatTool<InterpretWhiteboardArgs> {

    private final WhiteboardToolService whiteboardToolService;
    private final ToolExecutionContext context;

    public InterpretWhiteboardTool(WhiteboardToolService whiteboardToolService, ToolExecutionContext context) {
        this.whiteboardToolService = whiteboardToolService;
        this.context = context;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "interpret_whiteboard",
                "Renderiza la pizarra como imagen, ejecuta OCR y devuelve una interpretación semántica combinando texto y estructura. Solo lectura.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "whiteboardId", Map.of("type", "string"),
                                "conversationId", Map.of("type", "number"),
                                "documentId", Map.of("type", "number"),
                                "exerciseLabel", Map.of("type", "string")
                        ),
                        "required", List.of("whiteboardId")
                )
        );
    }

    @Override
    public Class<InterpretWhiteboardArgs> argumentType() {
        return InterpretWhiteboardArgs.class;
    }

    @Override
    public Object execute(InterpretWhiteboardArgs args) {
        return whiteboardToolService.interpret(args.whiteboardId(), context.require().userEmail());
    }
}
