package com.academy.chatservice.model;

import java.util.Map;

/**
 * SSE action event sent to the frontend.
 * type: OPEN_WHITEBOARD | UPDATE_WHITEBOARD
 */
public record WhiteboardAction(String type, Map<String, Object> payload) {}
