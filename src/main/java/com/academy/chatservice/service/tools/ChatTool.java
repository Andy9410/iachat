package com.academy.chatservice.service.tools;

import com.academy.chatservice.model.tools.ToolDefinition;

public interface ChatTool<T> {
    ToolDefinition definition();

    Class<T> argumentType();

    Object execute(T args);
}
