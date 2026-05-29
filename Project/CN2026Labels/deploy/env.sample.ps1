# Copy this file to env.ps1 and fill in the values for your group.
# Every other PowerShell deploy script imports this one with: . .\env.ps1

# --- project ---
$env:PROJECT_ID       = "CN2526-T3-G06"
$env:REGION           = "europe-west1"
$env:ZONE             = "europe-west1-b"

# --- resource names ---
$env:BUCKET           = "final_project_2526g6"
$env:CONFIG_BUCKET    = "final_project_2526g6-config"   # holds the JARs
$env:TOPIC            = "labels-tasks"
$env:SUBSCRIPTION     = "labels-tasks-sub"
$env:FIRESTORE_COL    = "labels-results"

# --- compute ---
$env:MIG_GRPC         = "grpc-server-mig"
$env:TPL_GRPC         = "grpc-server-tpl"
$env:MIG_LABELS       = "labels-app-mig"
$env:TPL_LABELS       = "labels-app-tpl"
$env:GRPC_PORT        = "8000"
$env:SERVICE_ACCOUNT  = "cn2026-runtime@$($env:PROJECT_ID).iam.gserviceaccount.com"
$env:MACHINE_TYPE     = "e2-small"
$env:IMAGE_FAMILY     = "debian-12"
$env:IMAGE_PROJECT    = "debian-cloud"

# --- cloud functions ---
$env:LOOKUP_FUNCTION  = "lookup"
$env:LOGGER_FUNCTION  = "pubsub-logger"
