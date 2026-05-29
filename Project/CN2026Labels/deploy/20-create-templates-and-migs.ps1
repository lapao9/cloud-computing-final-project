# Creates / replaces both instance templates and both MIGs.
$ErrorActionPreference = "Continue"
. "$PSScriptRoot\env.ps1"

$grpcStartup   = "$PSScriptRoot\grpc-server-startup.sh"
$labelsStartup = "$PSScriptRoot\labels-app-startup.sh"

# Common metadata consumed by both startup scripts.
$commonMeta = @(
  "cn-project-id=$($env:PROJECT_ID)",
  "cn-region=$($env:REGION)",
  "cn-zone=$($env:ZONE)",
  "cn-config-bucket=$($env:CONFIG_BUCKET)",
  "cn-firestore-col=$($env:FIRESTORE_COL)",
  "cn-mig-grpc=$($env:MIG_GRPC)",
  "cn-mig-labels=$($env:MIG_LABELS)"
)

# ---------------- gRPC server template + MIG ----------------
Write-Host "==> (re)creating template $($env:TPL_GRPC)"
gcloud compute instance-templates delete $env:TPL_GRPC --quiet 2>$null
$grpcMeta = ($commonMeta + @(
  "cn-bucket=$($env:BUCKET)",
  "cn-topic=$($env:TOPIC)",
  "cn-port=$($env:GRPC_PORT)"
)) -join ","

gcloud compute instance-templates create $env:TPL_GRPC `
  --machine-type=$env:MACHINE_TYPE `
  --image-family=$env:IMAGE_FAMILY --image-project=$env:IMAGE_PROJECT `
  --service-account=$env:SERVICE_ACCOUNT `
  --scopes=cloud-platform `
  --tags=grpc-server `
  --metadata-from-file=startup-script=$grpcStartup `
  --metadata=$grpcMeta

Write-Host "==> (re)creating MIG $($env:MIG_GRPC)"
$existsGrpc = gcloud compute instance-groups managed describe $env:MIG_GRPC --zone=$env:ZONE 2>$null
if ($existsGrpc) {
  gcloud compute instance-groups managed delete $env:MIG_GRPC --zone=$env:ZONE --quiet
}
gcloud compute instance-groups managed create $env:MIG_GRPC `
  --base-instance-name=grpc `
  --template=$env:TPL_GRPC `
  --size=1 `
  --zone=$env:ZONE

# ---------------- Labels-App template + MIG -----------------
Write-Host "==> (re)creating template $($env:TPL_LABELS)"
gcloud compute instance-templates delete $env:TPL_LABELS --quiet 2>$null
$labelsMeta = ($commonMeta + @(
  "cn-subscription=$($env:SUBSCRIPTION)",
  "cn-target-lang=pt",
  "cn-max-labels=10",
  "cn-min-score=0.6"
)) -join ","

gcloud compute instance-templates create $env:TPL_LABELS `
  --machine-type=$env:MACHINE_TYPE `
  --image-family=$env:IMAGE_FAMILY --image-project=$env:IMAGE_PROJECT `
  --service-account=$env:SERVICE_ACCOUNT `
  --scopes=cloud-platform `
  --tags=labels-app `
  --metadata-from-file=startup-script=$labelsStartup `
  --metadata=$labelsMeta

Write-Host "==> (re)creating MIG $($env:MIG_LABELS)"
$existsLabels = gcloud compute instance-groups managed describe $env:MIG_LABELS --zone=$env:ZONE 2>$null
if ($existsLabels) {
  gcloud compute instance-groups managed delete $env:MIG_LABELS --zone=$env:ZONE --quiet
}
gcloud compute instance-groups managed create $env:MIG_LABELS `
  --base-instance-name=labels `
  --template=$env:TPL_LABELS `
  --size=1 `
  --zone=$env:ZONE

Write-Host "==> done. Inspect with:"
Write-Host "    gcloud compute instance-groups managed list-instances $env:MIG_GRPC --zone=$env:ZONE"
Write-Host "    gcloud compute instance-groups managed list-instances $env:MIG_LABELS --zone=$env:ZONE"
