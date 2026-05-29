#!/usr/bin/env bash
# Copy to env.sh and fill in for your group.  All deploy scripts source it.

# --- project ---
export PROJECT_ID="cn2026-g06"
export REGION="europe-west1"
export ZONE="europe-west1-b"

# --- resource names ---
export BUCKET="cn2026-g06-images"
export CONFIG_BUCKET="cn2026-g06-config"            # holds the JARs
export TOPIC="labels-tasks"
export SUBSCRIPTION="labels-tasks-sub"
export FIRESTORE_COL="labels-results"

# --- compute ---
export MIG_GRPC="grpc-server-mig"
export TPL_GRPC="grpc-server-tpl"
export MIG_LABELS="labels-app-mig"
export TPL_LABELS="labels-app-tpl"
export GRPC_PORT="8000"
export SERVICE_ACCOUNT="cn2026-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

# --- cloud function ---
export LOOKUP_FUNCTION="lookup"
export LOGGER_FUNCTION="pubsub-logger"
