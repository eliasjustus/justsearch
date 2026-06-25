#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/reports/phase7/health"
mkdir -p "$OUT_DIR"

TARGET="${INFRA_HEALTH_GRPC_TARGET:-127.0.0.1:7443}"
METHOD="${INFRA_HEALTH_GRPC_METHOD:-io.justsearch.ipc.v1.InfraDiagnosticsService/CurrentSnapshot}"

if ! command -v grpcurl >/dev/null 2>&1; then
  echo "[capture-infra-health-grpc] grpcurl not found. Install grpcurl or run capture-infra-health.sh for REST evidence." >&2
  exit 1
fi

echo "[capture-infra-health-grpc] Capturing payload from $METHOD at $TARGET" >&2

if ! grpcurl -plaintext "$TARGET" "$METHOD" > "$OUT_DIR/health-grpc-response.json"; then
  echo "[capture-infra-health-grpc] grpcurl invocation failed. Ensure the diagnostics gRPC service is running and METHOD matches the deployed service." >&2
  exit 1
fi

echo "[capture-infra-health-grpc] Written $OUT_DIR/health-grpc-response.json" >&2
