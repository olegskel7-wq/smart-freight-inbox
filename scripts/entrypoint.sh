#!/bin/bash
set -e

# Start Ollama in the background
ollama serve &
SERVER_PID=$!

echo "Waiting for Ollama server to start..."
# ollama list talks to the local server; retry until it responds
until ollama list > /dev/null 2>&1; do
  sleep 2
done

# Ensure MODEL_NAME is set, fallback to default if not
MODEL_NAME=${MODEL_NAME:-qwen2.5:7b}
echo "Pulling model: $MODEL_NAME"
ollama pull "$MODEL_NAME"

# Ensure ADDITIONAL_MODEL is set, fallback to default if not
ADDITIONAL_MODEL=${ADDITIONAL_MODEL:-llama3.1:8b}
echo "Pulling model: $ADDITIONAL_MODEL"
ollama pull "$ADDITIONAL_MODEL"

# Phase 3.1: embedding model for the AI feedback loop (RAG over operator corrections)
EMBEDDING_MODEL=${EMBEDDING_MODEL:-nomic-embed-text}
echo "Pulling model: $EMBEDDING_MODEL"
ollama pull "$EMBEDDING_MODEL"

echo "All models ready. Ollama is accepting traffic."
wait $SERVER_PID
