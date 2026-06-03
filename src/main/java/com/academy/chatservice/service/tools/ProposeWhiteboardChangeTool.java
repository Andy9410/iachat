package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.ProposeWhiteboardChangeArgs;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProposeWhiteboardChangeTool implements ChatTool<ProposeWhiteboardChangeArgs> {
    private final WhiteboardService whiteboardService;
    private final ToolExecutionContext context;

    public ProposeWhiteboardChangeTool(WhiteboardService whiteboardService, ToolExecutionContext context) {
        this.whiteboardService = whiteboardService;
        this.context = context;
    }

    public ToolDefinition definition() {
        return new ToolDefinition(
                "propose_whiteboard_change",
                "Genera una sugerencia de cambio para la pizarra sin modificarla. El usuario debe confirmar para aplicar. Usá 'steps' para enviar una explicación paso a paso (array de strings, uno por paso).",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "whiteboardId", Map.of("type", "string"),
                                "instruction", Map.of("type", "string"),
                                "steps", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Lista de pasos para mostrar en la pizarra, uno por elemento")
                        ),
                        "required", List.of("whiteboardId", "instruction")
                )
        );
    }

    public Class<ProposeWhiteboardChangeArgs> argumentType() { return ProposeWhiteboardChangeArgs.class; }

    public Object execute(ProposeWhiteboardChangeArgs args) {
        return whiteboardService.proposeChange(args.whiteboardId(), args.instruction(), args.steps(), context.require().userEmail());
    }
}
