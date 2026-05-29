#!/bin/bash
exec > /var/log/cn2026-startup.log 2>&1
set -euxo pipefail

apt-get update -y
apt-get install -y openjdk-17-jre-headless

META="http://metadata.google.internal/computeMetadata/v1/instance/attributes"
HDR="Metadata-Flavor: Google"
get() { curl -sf -H "$HDR" "$META/$1"; }

export CN_PROJECT_ID=$(get cn-project-id)
export CN_SUBSCRIPTION=$(get cn-subscription)
export CN_FIRESTORE_COL=$(get cn-firestore-col)
export CN_TARGET_LANG=$(get cn-target-lang || echo pt)
export CN_MAX_LABELS=$(get cn-max-labels   || echo 10)
export CN_MIN_SCORE=$(get cn-min-score     || echo 0.6)

CONFIG_BUCKET=$(get cn-config-bucket)
mkdir -p /opt/cn2026 && cd /opt/cn2026
gsutil cp "gs://${CONFIG_BUCKET}/jars/labels-app.jar" labels-app.jar

export GOOGLE_CLOUD_DISABLE_DIRECT_PATH=true
exec java \
  -Dcom.google.cloud.firestore.disableDirectPath=true \
  -Dio.grpc.NameResolverProvider.enableServiceConfig=false \
  -jar labels-app.jar
