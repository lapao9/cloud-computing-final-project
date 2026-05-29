#!/usr/bin/env bash
# One-shot project bootstrap: APIs, service account, bucket, topic, subscription.
# Idempotent: re-running it is safe.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/env.sh"

gcloud config set project "$PROJECT_ID"

echo "==> enabling APIs"
gcloud services enable \
  compute.googleapis.com \
  pubsub.googleapis.com \
  storage.googleapis.com \
  firestore.googleapis.com \
  vision.googleapis.com \
  translate.googleapis.com \
  cloudfunctions.googleapis.com \
  cloudbuild.googleapis.com \
  run.googleapis.com \
  artifactregistry.googleapis.com

echo "==> creating service account (if missing)"
gcloud iam service-accounts describe "$SERVICE_ACCOUNT" >/dev/null 2>&1 || \
  gcloud iam service-accounts create cn2026-runtime --display-name="CN2026Labels runtime"

echo "==> binding roles"
for role in \
  roles/storage.objectAdmin \
  roles/pubsub.publisher \
  roles/pubsub.subscriber \
  roles/datastore.user \
  roles/cloudtranslate.user \
  roles/serviceusage.serviceUsageConsumer \
  roles/compute.instanceAdmin.v1 ; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$SERVICE_ACCOUNT" --role="$role" --condition=None >/dev/null
done

echo "==> buckets"
gcloud storage buckets describe "gs://$BUCKET"        >/dev/null 2>&1 || \
  gcloud storage buckets create "gs://$BUCKET"        --location="$REGION"
gcloud storage buckets describe "gs://$CONFIG_BUCKET" >/dev/null 2>&1 || \
  gcloud storage buckets create "gs://$CONFIG_BUCKET" --location="$REGION"

echo "==> Pub/Sub topic + shared subscription"
gcloud pubsub topics        describe "$TOPIC"        >/dev/null 2>&1 || \
  gcloud pubsub topics        create   "$TOPIC"
gcloud pubsub subscriptions describe "$SUBSCRIPTION" >/dev/null 2>&1 || \
  gcloud pubsub subscriptions create   "$SUBSCRIPTION" --topic="$TOPIC" --ack-deadline=60

echo "==> Firestore (Native mode, region $REGION)"
gcloud firestore databases describe --database="(default)" >/dev/null 2>&1 || \
  gcloud firestore databases create --location="$REGION"

echo "==> firewall: allow ${GRPC_PORT}/tcp on the gRPC server tag"
gcloud compute firewall-rules describe allow-grpc-${GRPC_PORT} >/dev/null 2>&1 || \
  gcloud compute firewall-rules create allow-grpc-${GRPC_PORT} \
    --allow=tcp:${GRPC_PORT} --target-tags=grpc-server --description="CN2026 gRPC"

echo "==> done"
