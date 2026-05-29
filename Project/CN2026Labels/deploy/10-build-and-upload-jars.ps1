# Build the three fat JARs and copy them to the config bucket.
# Startup scripts on the VMs pull them from there.
$ErrorActionPreference = "Continue"
. "$PSScriptRoot\env.ps1"

$ROOT = (Resolve-Path "$PSScriptRoot\..").Path

# Locate mvn: PATH first, then a couple of common install locations.
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
  $candidates = @(
    "D:\tools\maven\apache-maven-3.9.9\bin\mvn.cmd",
    "$env:ProgramFiles\Apache\maven\bin\mvn.cmd",
    "C:\apache-maven\bin\mvn.cmd"
  )
  $mvn = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $mvn) { throw "Maven not found. Install it and put mvn.cmd on PATH." }

Write-Host "==> mvn package"
& $mvn -f "$ROOT\pom.xml" -DskipTests clean package

$serverJar = "$ROOT\grpcServer\target\cn2026-labels-grpc-server-1.0-jar-with-dependencies.jar"
$workerJar = "$ROOT\labelsApp\target\cn2026-labels-worker-1.0-jar-with-dependencies.jar"
$clientJar = "$ROOT\client\target\cn2026-labels-client-1.0-jar-with-dependencies.jar"

foreach ($j in @($serverJar, $workerJar, $clientJar)) {
  if (-not (Test-Path $j)) { throw "Build artifact missing: $j" }
}

Write-Host "==> uploading JARs to gs://$($env:CONFIG_BUCKET)/jars/"
gcloud storage cp $serverJar "gs://$($env:CONFIG_BUCKET)/jars/grpc-server.jar"
gcloud storage cp $workerJar "gs://$($env:CONFIG_BUCKET)/jars/labels-app.jar"
gcloud storage cp $clientJar "gs://$($env:CONFIG_BUCKET)/jars/client.jar"

Write-Host "==> JARs uploaded."
