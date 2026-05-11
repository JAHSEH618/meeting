#!/bin/sh
# Initialize Vault dev instance for local development
# Meeting Intelligence System — KMS (transit engine) for speaker embedding envelope encryption

set -e

export VAULT_ADDR="${VAULT_ADDR:-http://localhost:8200}"
export VAULT_TOKEN="${VAULT_DEV_ROOT_TOKEN:-meeting-dev-root-token}"

echo "Enabling transit engine for speaker embedding KMS..."
vault secrets enable -path=meeting-kms transit 2>/dev/null || true

echo "Creating AES-256-GCM key for speaker embedding encryption..."
vault write -f meeting-kms/keys/speaker-embedding type=aes256-gcm256 auto_rotate_period=2160h 2>/dev/null || true

echo "Vault KMS ready: meeting-kms/speaker-embedding"
