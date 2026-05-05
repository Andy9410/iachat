# chat-service

Servicio de chat inteligente construido con Spring Boot 3.2.5 y Java 21. Expone una API REST que procesa mensajes de usuario y genera respuestas usando un modelo LLM local a través de Ollama. La arquitectura está preparada para RAG (Retrieval-Augmented Generation) con PostgreSQL y pgvector, permitiendo enriquecer las respuestas con contexto de documentos almacenados como embeddings vectoriales.

## Setup

**Requisitos:** Java 21, Maven, [Ollama](https://ollama.com) con el modelo `qwen2.5:14b` descargado.

```bash
# 1. Descargar el modelo en Ollama
ollama pull qwen2.5:14b

# 2. Compilar y ejecutar
./mvnw spring-boot:run
```

El servicio queda disponible en `http://localhost:8080`.

```bash
# Enviar un mensaje
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "¿Qué es la fotosíntesis?"}'
```

Para correr los tests (usan un cliente mock, no requieren Ollama):

```bash
./mvnw test
```
