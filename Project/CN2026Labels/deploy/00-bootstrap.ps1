# One-shot project bootstrap: APIs, service account, buckets, topic,
# subscription, Firestore, firewall.  Idempotent: re-running is safe.
$ErrorActionPreference = "Continue"
. "$PSScriptRoot\env.ps1"

gcloud config set project $env:PROJECT_ID | Out-Null

Write-Host "==> enabling APIs"
gcloud services enable `
  compute.googleapis.com `
  pubsub.googleapis.com `
  storage.googleapis.com `
  firestore.googleapis.com `
  vision.googleapis.com `
  translate.googleapis.com `
  cloudfunctions.googleapis.com `
  cloudbuild.googleapis.com `
  run.googleapis.com `
  artifactregistry.googleapis.com `
  eventarc.googleapis.com

Write-Host "==> creating service account (if missing)"
$saExists = gcloud iam service-accounts describe $env:SERVICE_ACCOUNT 2>$null
if (-not $saExists) {
  gcloud iam service-accounts create cn2026-runtime `
    --display-name="CN2026Labels runtime" | Out-Null
}

Write-Host "==> binding roles"
$roles = @(
  "roles/storage.objectAdmin",
  "roles/pubsub.publisher",
  "roles/pubsub.subscriber",
  "roles/datastore.user",
  "roles/cloudtranslate.user",
  "roles/serviceusage.serviceUsageConsumer",
  "roles/compute.instanceAdmin.v1"
)
foreach ($r in $roles) {
  gcloud projects add-iam-policy-binding $env:PROJECT_ID `
    --member="serviceAccount:$($env:SERVICE_ACCOUNT)" `
    --role=$r --condition=None | Out-Null
}

Write-Host "==> buckets"
$b1 = gcloud storage buckets describe "gs://$($env:BUCKET)" 2>$null
if (-not $b1) {
  gcloud storage buckets create "gs://$($env:BUCKET)" --location=$env:REGION
}
$b2 = gcloud storage buckets describe "gs://$($env:CONFIG_BUCKET)" 2>$null
if (-not $b2) {
  gcloud storage buckets create "gs://$($env:CONFIG_BUCKET)" --location=$env:REGION
}

Write-Host "==> Pub/Sub topic + shared subscription"
$t = gcloud pubsub topics describe $env:TOPIC 2>$null
if (-not $t) {
  gcloud pubsub topics create $env:TOPIC | Out-Null
}
$s = gcloud pubsub subscriptions describe $env:SUBSCRIPTION 2>$null
if (-not $s) {
  gcloud pubsub subscriptions create $env:SUBSCRIPTION --topic=$env:TOPIC --ack-deadline=60 | Out-Null
}

Write-Host "==> Firestore (Native mode, $env:REGION)"
$fs = gcloud firestore databases describe --database="(default)" 2>$null
if (-not $fs) {
  gcloud firestore databases create --location=$env:REGION
}

Write-Host "==> firewall rule allow-grpc-$($env:GRPC_PORT) on tag grpc-server"
$fw = gcloud compute firewall-rules describe "allow-grpc-$($env:GRPC_PORT)" 2>$null
if (-not $fw) {
  gcloud compute firewall-rules create "allow-grpc-$($env:GRPC_PORT)" `
    --allow=tcp:$env:GRPC_PORT `
    --target-tags=grpc-server `
    --description="CN2026 gRPC"
}

Write-Host "==> bootstrap done."
