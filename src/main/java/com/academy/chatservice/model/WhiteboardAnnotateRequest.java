package com.academy.chatservice.model;

/**
 * Request to ask the AI to annotate the whiteboard.
 * Sent when teacher mode triggers or the student selects an element and asks the AI.
 */
public record WhiteboardAnnotateRequest(
        Long conversationId,
        String question,          // optional specific question about the board
        String selectedContent,   // optional: content of selected element
        String selectedType,      // optional: type of selected element
        boolean socraticMode      // if true, AI guides without giving answers
) {}
