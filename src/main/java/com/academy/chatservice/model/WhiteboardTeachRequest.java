package com.academy.chatservice.model;

public record WhiteboardTeachRequest(
    String userInput,   // null en el primer paso; respuesta del alumno en pasos siguientes
    int stepIndex,      // 0 en el primer paso
    String topic        // tema original (solo relevante en step 0, puede ser null)
) {}
