#!/usr/bin/env bash
# Delete everything that 00..30 created.  Useful before the demo to start fresh.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/env.sh"

gcloud compute instance-groups managed delete "$MIG_GRPC"   --zone="$ZONE" --quiet || true
gcloud compute instance-groups managed delete "$MIG_LABELS" --zone="$ZONE" --quiet || true
gcloud compute instance-templates delete "$TPL_GRPC"   --quiet || true
gcloud compute instance-templates delete "$TPL_LABELS" --quiet || true
gcloud functions delete "$LOOKUP_FUNCTION" --region="$REGION" --quiet || true
gcloud functions delete "$LOGGER_FUNCTION" --region="$REGION" --quiet || true
gcloud pubsub subscriptions delete "$SUBSCRIPTION" --quiet || true
gcloud pubsub topics delete "$TOPIC" --quiet || true
gcloud compute firewall-rules delete "allow-grpc-${GRPC_PORT}" --quiet || true
echo "(buckets and Firestore are NOT deleted automatically)"
