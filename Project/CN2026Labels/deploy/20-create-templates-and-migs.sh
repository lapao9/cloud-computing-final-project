#!/usr/bin/env bash
# Creates / replaces both instance templates and both MIGs.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/env.sh"

MACHINE_TYPE="${MACHINE_TYPE:-e2-small}"
IMAGE_FAMILY="${IMAGE_FAMILY:-debian-12}"
IMAGE_PROJECT="${IMAGE_PROJECT:-debian-cloud}"

# Shared metadata (env vars consumed by the startup scripts)
COMMON_META=(
  "cn-project-id=${PROJECT_ID}"
  "cn-region=${REGION}"
  "cn-zone=${ZONE}"
  "cn-config-bucket=${CONFIG_BUCKET}"
  "cn-firestore-col=${FIRESTORE_COL}"
  "cn-mig-grpc=${MIG_GRPC}"
  "cn-mig-labels=${MIG_LABELS}"
)

# ===================== gRPC server template + MIG =====================

echo "==> (re)creating template ${TPL_GRPC}"
gcloud compute instance-templates delete "$TPL_GRPC" --quiet >/dev/null 2>&1 || true
gcloud compute instance-templates create "$TPL_GRPC" \
  --machine-type="$MACHINE_TYPE" \
  --image-family="$IMAGE_FAMILY" --image-project="$IMAGE_PROJECT" \
  --service-account="$SERVICE_ACCOUNT" \
  --scopes=cloud-platform \
  --tags=grpc-server \
  --metadata-from-file=startup-script="$DIR/grpc-server-startup.sh" \
  --metadata="$(IFS=,; echo "${COMMON_META[*]},cn-bucket=${BUCKET},cn-topic=${TOPIC},cn-port=${GRPC_PORT}")"

echo "==> (re)creating MIG ${MIG_GRPC}"
gcloud compute instance-groups managed describe "$MIG_GRPC" --zone="$ZONE" >/dev/null 2>&1 \
  && gcloud compute instance-groups managed delete "$MIG_GRPC" --zone="$ZONE" --quiet
gcloud compute instance-groups managed create "$MIG_GRPC" \
  --base-instance-name="grpc" \
  --template="$TPL_GRPC" \
  --size=1 \
  --zone="$ZONE"

# ===================== Labels-App template + MIG =====================

echo "==> (re)creating template ${TPL_LABELS}"
gcloud compute instance-templates delete "$TPL_LABELS" --quiet >/dev/null 2>&1 || true
gcloud compute instance-templates create "$TPL_LABELS" \
  --machine-type="$MACHINE_TYPE" \
  --image-family="$IMAGE_FAMILY" --image-project="$IMAGE_PROJECT" \
  --service-account="$SERVICE_ACCOUNT" \
  --scopes=cloud-platform \
  --tags=labels-app \
  --metadata-from-file=startup-script="$DIR/labels-app-startup.sh" \
  --metadata="$(IFS=,; echo "${COMMON_META[*]},cn-subscription=${SUBSCRIPTION},cn-target-lang=pt,cn-max-labels=10,cn-min-score=0.6")"

echo "==> (re)creating MIG ${MIG_LABELS}"
gcloud compute instance-groups managed describe "$MIG_LABELS" --zone="$ZONE" >/dev/null 2>&1 \
  && gcloud compute instance-groups managed delete "$MIG_LABELS" --zone="$ZONE" --quiet
gcloud compute instance-groups managed create "$MIG_LABELS" \
  --base-instance-name="labels" \
  --template="$TPL_LABELS" \
  --size=1 \
  --zone="$ZONE"

echo "==> done.  Use 'gcloud compute instance-groups managed list-instances' to inspect."
