package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, ChatTool<?>> tools;

    public ToolRegistry(ObjectMapper objectMapper, List<ChatTool<?>> availableTools) {
        this.objectMapper = objectMapper;
        this.tools = new LinkedHashMap<>();
        for (ChatTool<?> tool : availableTools) {
            this.tools.put(tool.definition().name(), tool);
        }
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(ChatTool::definition).toList();
    }

    public Object execute(String name, String rawArguments) {
        ChatTool<?> tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool no registrada: " + name);
        }
        return executeTyped(tool, rawArguments);
    }

    private <T> Object executeTyped(ChatTool<T> tool, String rawArguments) {
        try {
            T args = objectMapper.readValue(rawArguments, tool.argumentType());
            return tool.execute(args);
        } catch (Exception e) {
            throw new IllegalArgumentException("Argumentos inválidos para tool " + tool.definition().name(), e);
        }
    }
}
