package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.GetExerciseWhiteboardArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GetExerciseWhiteboardTool implements ChatTool<GetExerciseWhiteboardArgs> {
    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public GetExerciseWhiteboardTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    public ToolDefinition definition() {
        return new ToolDefinition(
                "get_exercise_whiteboard",
                "Obtiene la pizarra asociada a un ejercicio específico. Solo lectura.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "conversationId", Map.of("type", "number"),
                                "exerciseLabel", Map.of("type", "string")
                        ),
                        "required", List.of("conversationId", "exerciseLabel")
                )
        );
    }

    public Class<GetExerciseWhiteboardArgs> argumentType() { return GetExerciseWhiteboardArgs.class; }

    public Object execute(GetExerciseWhiteboardArgs args) {
        var ctx = context.require();
        Long conversationId = args.conversationId() != null ? args.conversationId() : ctx.conversationId();
        return whiteboardService.getExerciseWhiteboard(conversationId, args.exerciseLabel(), ctx.userEmail());
    }
}
