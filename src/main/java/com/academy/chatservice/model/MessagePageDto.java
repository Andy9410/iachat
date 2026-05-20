package com.academy.chatservice.model;

import java.util.List;

public record MessagePageDto(List<MessageDto> messages, boolean hasMore) {}
