# Deletes everything that 00..30 created. Useful before the demo to start clean.
$ErrorActionPreference = "SilentlyContinue"
. "$PSScriptRoot\env.ps1"

gcloud compute instance-groups managed delete $env:MIG_GRPC   --zone=$env:ZONE --quiet
gcloud compute instance-groups managed delete $env:MIG_LABELS --zone=$env:ZONE --quiet
gcloud compute instance-templates delete $env:TPL_GRPC   --quiet
gcloud compute instance-templates delete $env:TPL_LABELS --quiet
gcloud functions delete $env:LOOKUP_FUNCTION --region=$env:REGION --quiet
gcloud functions delete $env:LOGGER_FUNCTION --region=$env:REGION --quiet
gcloud pubsub subscriptions delete $env:SUBSCRIPTION --quiet
gcloud pubsub topics delete $env:TOPIC --quiet
gcloud compute firewall-rules delete "allow-grpc-$($env:GRPC_PORT)" --quiet

Write-Host "(buckets and Firestore are NOT deleted automatically)"
