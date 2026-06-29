package com.academy.chatservice.config;

import com.academy.chatservice.model.ChatResponse;
import com.academy.chatservice.service.openrouter.OpenRouterApiException;
import com.academy.chatservice.service.openrouter.OpenRouterUnavailableException;
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ChatResponse> handleValidation(MethodArgumentNotValidException ex) {
        var message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation error");
        return ResponseEntity.badRequest().body(new ChatResponse(message, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ChatResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ChatResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ChatResponse> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ChatResponse(ex.getReason(), null));
    }

    @ExceptionHandler(OpenRouterUnavailableException.class)
    public ResponseEntity<ChatResponse> handleOpenRouterUnavailable(OpenRouterUnavailableException ex) {
        log.warn("OpenRouter no disponible: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ChatResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(OpenRouterApiException.class)
    public ResponseEntity<ChatResponse> handleOpenRouterApi(OpenRouterApiException ex) {
        log.warn("OpenRouter devolvio error upstream: status={} body={}",
                ex.statusCode(), truncate(ex.responseBody()));
        HttpStatus status = ex.statusCode() == 429
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(new ChatResponse("OpenRouter no pudo responder en este momento. Intentá nuevamente en unos segundos.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleGeneric(Exception ex) {
        OpenRouterUnavailableException unavailable = findCause(ex, OpenRouterUnavailableException.class);
        if (unavailable != null) {
            return handleOpenRouterUnavailable(unavailable);
        }
        OpenRouterApiException apiException = findCause(ex, OpenRouterApiException.class);
        if (apiException != null) {
            return handleOpenRouterApi(apiException);
        }

        log.error("Error no controlado: {}", ex.getMessage(), ex);
        Sentry.captureException(ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ChatResponse("Error interno del servidor", null));
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(300, value.length()));
    }
}
