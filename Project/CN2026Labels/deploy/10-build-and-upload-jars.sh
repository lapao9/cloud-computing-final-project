#!/usr/bin/env bash
# Build the two fat JARs and copy them to the config bucket.
# Startup scripts on the VMs pull them from there.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DIR/env.sh"

ROOT="$DIR/.."
mvn -f "$ROOT/pom.xml" -DskipTests clean package

SERVER_JAR="$ROOT/grpcServer/target/cn2026-labels-grpc-server-1.0-jar-with-dependencies.jar"
WORKER_JAR="$ROOT/labelsApp/target/cn2026-labels-worker-1.0-jar-with-dependencies.jar"
CLIENT_JAR="$ROOT/client/target/cn2026-labels-client-1.0-jar-with-dependencies.jar"

gcloud storage cp "$SERVER_JAR" "gs://$CONFIG_BUCKET/jars/grpc-server.jar"
gcloud storage cp "$WORKER_JAR" "gs://$CONFIG_BUCKET/jars/labels-app.jar"
gcloud storage cp "$CLIENT_JAR" "gs://$CONFIG_BUCKET/jars/client.jar"
echo "==> JARs uploaded to gs://$CONFIG_BUCKET/jars/"
