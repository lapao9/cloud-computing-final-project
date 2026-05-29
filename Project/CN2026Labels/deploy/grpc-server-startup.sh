#!/bin/bash
# Startup script copied verbatim into the VM instance template.
# Reads its configuration from instance metadata (set by 20-create-templates.sh).
exec > /var/log/cn2026-startup.log 2>&1
set -euxo pipefail

apt-get update -y
apt-get install -y openjdk-17-jre-headless

META="http://metadata.google.internal/computeMetadata/v1/instance/attributes"
HDR="Metadata-Flavor: Google"
get() { curl -sf -H "$HDR" "$META/$1"; }

export CN_PROJECT_ID=$(get cn-project-id)
export CN_BUCKET=$(get cn-bucket)
export CN_TOPIC=$(get cn-topic)
export CN_ZONE=$(get cn-zone)
export CN_REGION=$(get cn-region)
export CN_MIG_GRPC=$(get cn-mig-grpc)
export CN_MIG_LABELS=$(get cn-mig-labels)
export CN_PORT=$(get cn-port)
export CN_FIRESTORE_COL=$(get cn-firestore-col)

CONFIG_BUCKET=$(get cn-config-bucket)
mkdir -p /opt/cn2026 && cd /opt/cn2026
gsutil cp "gs://${CONFIG_BUCKET}/jars/grpc-server.jar" grpc-server.jar

export GOOGLE_CLOUD_DISABLE_DIRECT_PATH=true
exec java \
  -Dcom.google.cloud.firestore.disableDirectPath=true \
  -Dio.grpc.NameResolverProvider.enableServiceConfig=false \
  -jar grpc-server.jar
