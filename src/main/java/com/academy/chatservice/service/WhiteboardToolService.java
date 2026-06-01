package com.academy.chatservice.service;

import com.academy.chatservice.model.WhiteboardInterpretRequest;
import com.academy.chatservice.model.WhiteboardInterpretationResponse;
import org.springframework.stereotype.Service;

@Service
public class WhiteboardToolService {

    private final WhiteboardService whiteboardService;

    public WhiteboardToolService(WhiteboardService whiteboardService) {
        this.whiteboardService = whiteboardService;
    }

    public WhiteboardInterpretationResponse interpret(WhiteboardInterpretRequest request, String userEmail) {
        return whiteboardService.interpret(request, userEmail);
    }

    public WhiteboardInterpretationResponse interpret(String whiteboardId, String userEmail) {
        return whiteboardService.interpret(whiteboardId, userEmail);
    }
}
