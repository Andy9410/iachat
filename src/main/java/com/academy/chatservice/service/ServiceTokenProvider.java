package com.academy.chatservice.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Genera un JWT de larga duración para llamadas internas entre servicios.
 * Usa el mismo JWT_SECRET que el frontend/auth para que document-service pueda validarlo.
 */
@Component
public class ServiceTokenProvider {

    private final String token;

    public ServiceTokenProvider(@Value("${jwt.secret}") String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long expiry = System.currentTimeMillis() + 30L * 24 * 3600 * 1000; // 30 días
        this.token = Jwts.builder()
                .subject("service@chat-service.internal")
                .expiration(new Date(expiry))
                .signWith(key)
                .compact();
    }

    public String getServiceToken() {
        return token;
    }
}
