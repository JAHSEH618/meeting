#!/bin/sh
# Initialize MinIO buckets for local development
# Meeting Intelligence System

set -e

MC="mc alias set local http://localhost:9000 ${MINIO_ROOT_USER:-minioadmin} ${MINIO_ROOT_PASSWORD:-minioadmin}"

# Wait for MinIO to be ready
until $MC 2>/dev/null; do
  echo "Waiting for MinIO..."
  sleep 2
done

echo "Creating buckets..."
mc mb local/meeting-audio-auska --ignore-existing
mc mb local/meeting-artifacts --ignore-existing
mc mb local/meeting-exports --ignore-existing

echo "Buckets ready: meeting-audio-auska, meeting-artifacts, meeting-exports"
