    package com.academy.chatservice.service;

import java.util.List;

public interface EmbeddingClient {
    List<Float> embed(String text);
}
