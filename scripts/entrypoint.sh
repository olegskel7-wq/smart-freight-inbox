#!/bin/bash
set -e

ollama serve &
SERVER_PID=$!

echo "Waiting for Ollama server to start..."
until curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; do
  sleep 2
done

echo "Pulling model: $MODEL_NAME"
ollama pull "$MODEL_NAME"

echo "Pulling model: $ADDITIONAL_MODEL"
ollama pull "$ADDITIONAL_MODEL"

echo "All models ready. Ollama is accepting traffic."
wait $SERVER_PID
