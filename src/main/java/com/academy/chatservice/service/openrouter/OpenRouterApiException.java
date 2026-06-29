package com.academy.chatservice.service.openrouter;

public class OpenRouterApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public OpenRouterApiException(int statusCode, String responseBody) {
        super("OpenRouter respondio con HTTP " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
