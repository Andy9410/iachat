package com.academy.chatservice.model.tools;

import java.util.List;

public record ProposeWhiteboardChangeArgs(String whiteboardId, String instruction, List<String> steps) {}
