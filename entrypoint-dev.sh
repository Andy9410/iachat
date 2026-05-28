#!/bin/bash
set -e

echo "▶ Iniciando chat-service (modo desarrollo)..."
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dserver.port=${SERVER_PORT:-8080} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-dev}" &
SPRING_PID=$!

trap "kill $SPRING_PID 2>/dev/null; exit 0" SIGTERM SIGINT

echo "▶ Observando cambios en src/..."
while inotifywait -r -e close_write --include='.*\.java$' src/ 2>/dev/null; do
    sleep 0.3
    echo "▶ Cambio detectado — recompilando..."
    mvn compile -q && echo "✓ Recompilado — DevTools reinicia el contexto"
done

wait $SPRING_PID
