package com.academy.chatservice.model;

public record WhiteboardInterpretationResponse(
        String type,
        String whiteboardId,
        String title,
        String exerciseLabel,
        Long documentId,
        String equation,
        String ocrText,
        String structuredElements,
        String semanticSummary,
        double confidence,
        String reason
) {
    // Constructor legacy sin reason (backward compatibility)
    public WhiteboardInterpretationResponse(
            String type, String whiteboardId, String title, String exerciseLabel,
            Long documentId, String equation, String ocrText,
            String structuredElements, String semanticSummary, double confidence) {
        this(type, whiteboardId, title, exerciseLabel, documentId, equation, ocrText,
             structuredElements, semanticSummary, confidence, "");
    }
}
