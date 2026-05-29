# Deploys the Lookup HTTP function and the optional Pub/Sub logger.
$ErrorActionPreference = "Continue"
. "$PSScriptRoot\env.ps1"

$lookupSrc = (Resolve-Path "$PSScriptRoot\..\cloudFunctions\lookup").Path
$loggerSrc = (Resolve-Path "$PSScriptRoot\..\cloudFunctions\pubsubLogger").Path

Write-Host "==> deploying lookup function"
gcloud functions deploy $env:LOOKUP_FUNCTION `
  --gen2 `
  --region=$env:REGION `
  --runtime=nodejs20 `
  --source=$lookupSrc `
  --entry-point=lookup `
  --trigger-http `
  --allow-unauthenticated `
  --service-account=$env:SERVICE_ACCOUNT `
  --set-env-vars="PROJECT_ID=$($env:PROJECT_ID),ZONE=$($env:ZONE),MIG_NAME=$($env:MIG_GRPC),GRPC_PORT=$($env:GRPC_PORT)"

Write-Host ""
Write-Host "Lookup URL:"
gcloud functions describe $env:LOOKUP_FUNCTION --region=$env:REGION --format="value(serviceConfig.uri)"

Write-Host ""
Write-Host "==> deploying pubsub-logger (optional)"
gcloud functions deploy $env:LOGGER_FUNCTION `
  --gen2 `
  --region=$env:REGION `
  --runtime=nodejs20 `
  --source=$loggerSrc `
  --entry-point=logRequest `
  --trigger-topic=$env:TOPIC `
  --service-account=$env:SERVICE_ACCOUNT `
  --set-env-vars="PROJECT_ID=$($env:PROJECT_ID),COLLECTION=requests-log"
