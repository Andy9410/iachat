package com.academy.chatservice.service.tools;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ToolExecutionContext {
    private final ThreadLocal<Context> current = new ThreadLocal<>();

    public <T> T withContext(String userEmail, Long conversationId, Supplier<T> supplier) {
        current.set(new Context(userEmail, conversationId));
        try {
            return supplier.get();
        } finally {
            current.remove();
        }
    }

    public Context require() {
        Context context = current.get();
        if (context == null) {
            throw new IllegalStateException("No hay contexto de ejecución para tools");
        }
        return context;
    }

    public record Context(String userEmail, Long conversationId) {}
}
