package com.academy.chatservice.service;

import com.academy.chatservice.model.WhiteboardInterpretationResponse;

public interface VisionModelClient {
    WhiteboardInterpretationResponse interpretWhiteboardImage(String imageBase64, String whiteboardId);
}
