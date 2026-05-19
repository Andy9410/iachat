● Tengo el cuadro completo. El esquema de BD ya está diseñado bien, pero JPA/PostgreSQL están comentados en el pom.xml y no hay capa de persistencia todavía. Te propongo el plan con las políticas antes de implementar.  
                                                                                                                                                                                                                           
  ---                                                                                                                                                                                                                      
  Plan RAG + Políticas                                                                                                                                                                                                     
                                                                                                                                                                                                                           
  Fase 1 — Activar la capa de datos (prerequisito)                                                                                                                                                                         
                                                                                                                                                                                                                           
  - Descomentar JPA + PostgreSQL en pom.xml, agregar Flyway y pgvector JDBC                                                                                                                                                
  - Mover la migración de db/migrations/ → src/main/resources/db/migration/ (convención Flyway)                                                                                                                            
  - Crear entidades JPA: Conversation, Message, MessageEmbedding                                                                                                                                                           
                                                                                                                                                                                                                           
  ---                                                                                                                                                                                                                      
  Política 1 — Almacenamiento de contexto de conversaciones                                                                                                                                                                
                                                                                                                                                                                                                           
  ┌───────────────────────────────────────────────────┬─────────────────────────────────────┐
  │                       Regla                       │                Valor                │                                                                                                                              
  ├───────────────────────────────────────────────────┼─────────────────────────────────────┤
  │ conversationId (UUID) en request/response         │ obligatorio desde el primer mensaje │
  ├───────────────────────────────────────────────────┼─────────────────────────────────────┤
  │ Cada par user+assistant → se persiste en messages │ siempre                             │                                                                                                                              
  ├───────────────────────────────────────────────────┼─────────────────────────────────────┤                                                                                                                              
  │ Máximo mensajes por conversación                  │ 200                                 │                                                                                                                              
  ├───────────────────────────────────────────────────┼─────────────────────────────────────┤                                                                                                                              
  │ TTL de conversaciones inactivas                   │ 30 días (purga con scheduled job)   │
  └───────────────────────────────────────────────────┴─────────────────────────────────────┘                                                                                                                              
   
  El endpoint POST /chat pasa a aceptar { "conversationId": "uuid-o-null", "message": "..." } y retorna el conversationId para que el cliente lo reutilice.                                                                
                  
  ---                                                                                                                                                                                                                      
  Política 2 — Compactación de contexto (context window)
                                                        
  Dos triggers en cascada:
                                                                                                                                                                                                                           
  1. Ventana deslizante — al armar el prompt, se envían solo los últimos 20 mensajes (≈ 8k tokens estimados). Los más viejos existen en DB pero no se inyectan al LLM.                                                     
  2. Compactación por resumen — cuando una conversación supera 50 mensajes, los primeros 30 se reemplazan por un resumen generado con el mismo LLM y se marcan compacted = true. El resumen cuenta como 1 mensaje de rol   
  system.                                                                                                                                                                                                                  
                  
  Esto mantiene el contexto manejable sin perder el hilo semántico de la conversación.                                                                                                                                     
                  
  ---                                                                                                                                                                                                                      
  Política 3 — Sustentabilidad y mantenibilidad
                                               
  Arquitectura (ports & adapters):
  - LLMClient — ya existe ✅                                                                                                                                                                                                
  - EmbeddingClient — nueva interfaz (misma idea, implementación Ollama con nomic-embed-text)
  - ConversationRepository, MessageRepository — Spring Data JPA, sin SQL crudo en servicios                                                                                                                                
  - RAG desactivable con rag.enabled=false en config, sin tocar código                                                                                                                                                     
                                                                                                                                                                                                                           
  Base de datos:                                                                                                                                                                                                           
  - Una migración por cambio de esquema, nunca editar migraciones ya aplicadas                                                                                                                                             
  - Nombres de migración: V2__add_compacted_flag_to_messages.sql, etc.        

  Observabilidad:                                                                                                                                                                                                          
  - correlationId (MDC) propagado en cada request para trazar logs end-to-end
  - Métricas de latencia LLM vía Actuator/Micrometer                                                                                                                                                                       
                  
  ---                                                                                                                                                                                                                      
  Orden de implementación sugerido
                                                                                                                                                                                                                           
  1. pom.xml + JPA entities + repositorios
  2. ChatRequest/Response con conversationId                                                                                                                                                                               
  3. Persistir mensajes (sin RAG aún)
  4. EmbeddingClient + generar embeddings al guardar                                                                                                                                                                       
  5. Recuperar contexto por similitud (RAG real)                                                                                                                                                                           
  6. Lógica de compactación                                                                                                                                                                                                
  7. Scheduled job de purga