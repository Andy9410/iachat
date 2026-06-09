package com.academy.chatservice.model;

import java.util.List;

public record WhiteboardEntriesRequest(List<EntryRequest> entries) {
    public record EntryRequest(String type, String author, String content, int orderIndex) {}
}
