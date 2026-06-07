package com.academy.chatservice.model;

import java.util.List;

public record WhiteboardTeachResponse(
    List<WhiteboardEntryDto> entries,
    String pauseQuestion,   // null cuando isComplete = true
    boolean isComplete,
    int nextStepIndex
) {}
