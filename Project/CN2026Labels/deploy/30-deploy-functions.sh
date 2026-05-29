#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/env.sh"

echo "==> deploying lookup function"
gcloud functions deploy "$LOOKUP_FUNCTION" \
  --gen2 --region="$REGION" --runtime=nodejs20 \
  --source="$DIR/../cloudFunctions/lookup" \
  --entry-point=lookup \
  --trigger-http --allow-unauthenticated \
  --service-account="$SERVICE_ACCOUNT" \
  --set-env-vars="PROJECT_ID=${PROJECT_ID},ZONE=${ZONE},MIG_NAME=${MIG_GRPC},GRPC_PORT=${GRPC_PORT}"

echo
echo "Lookup URL:"
gcloud functions describe "$LOOKUP_FUNCTION" --region="$REGION" \
  --format='value(serviceConfig.uri)'

echo
echo "==> deploying pubsub-logger (optional)"
gcloud functions deploy "$LOGGER_FUNCTION" \
  --gen2 --region="$REGION" --runtime=nodejs20 \
  --source="$DIR/../cloudFunctions/pubsubLogger" \
  --entry-point=logRequest \
  --trigger-topic="$TOPIC" \
  --service-account="$SERVICE_ACCOUNT" \
  --set-env-vars="PROJECT_ID=${PROJECT_ID},COLLECTION=requests-log"
