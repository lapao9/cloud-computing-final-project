# CN2026Labels (ISEL - Computação na Nuvem 2025/2026)

Elastic distributed image-labelling system on Google Cloud Platform.

## Modules

| Path | Purpose |
|------|---------|
| `grpcContract/` | Protobuf contract (`LabelsService` SF + `ScaleService` SG) shared by server and client |
| `grpcServer/`   | Java gRPC server. Hosts SF and SG. Streams images to Cloud Storage, publishes Pub/Sub tasks, reads Firestore for queries, resizes MIGs |
| `labelsApp/`    | Java Pub/Sub subscriber. For each task: fetch image -> Vision API (labels) -> Translation API (en->pt) -> Firestore |
| `client/`       | Java console client. Calls Lookup function, picks an IP, exercises every SF + SG operation |
| `cloudFunctions/lookup/`         | HTTP function returning the IPs of the running gRPC MIG VMs |
| `cloudFunctions/pubsubLogger/`   | Optional Pub/Sub-triggered function that logs each task in Firestore (`requests-log`) |
| `deploy/`       | Shell scripts to bootstrap APIs, build JARs, create templates / MIGs / functions, tear down |
| `report/`       | Final 10-15 page report (Markdown source) |

## Architecture (matches Figure 1 of the enunciado)

```
                            +-----------------+
   (1) HTTP lookup -------> | Cloud Function  |--(2) list MIG instances----+
       |                    |   (HTTP)        |                            |
       v                    +-----------------+                            v
   +----------+                                              +-------------------+
   |  Client  |---- gRPC SubmitImage stream (3.1) ---------->|  gRPC Server MIG  |
   |          |                                              |   (Compute Engine)|
   |          |<---- requestId ------------------------------|                   |
   +----------+                                              +---------+---------+
                                                                       |
                                                                  (3.2)| publish task
                                                                       v
                                                              +-----------------+
                                                              |   Pub/Sub       |
                                                              +-----+-----+-----+
                                                                    |     |
                                                            (4.1)   |     | (4.0 optional)
                                                                    v     v
                                                          +---------------------+   +------------------+
                                                          |  Labels-App MIG     |   | Pub/Sub Function |
                                                          |  (Compute Engine)   |   |  (logger)        |
                                                          +-+----+--------------+   +---------+--------+
                                                            |    |  (6) Vision + Translate              (5) write
                                                            |    +-->                                        v
                                                       (4.2)|    +-->        +----------+   +-----------+
                                                            +--->| Cloud     |   (7)     |    Firestore     |
                                                                 | Storage   |---------->|                  |
                                                                 +-----------+           +--------+---------+
                                                                                                  ^ (8) Get/Query
                                                                                                  |
                                                                                          +-------+--------+
                                                                                          |  gRPC Server   |
                                                                                          +----------------+
```

## Quick start

> Two parallel sets of deploy scripts are provided: **`.sh`** (Linux/macOS/Git Bash)
> and **`.ps1`** (Windows PowerShell). Pick whichever matches your shell.

### PowerShell (Windows)

```powershell
cd deploy
Copy-Item env.sample.ps1 env.ps1                  # then edit env.ps1
.\00-bootstrap.ps1                                # APIs, SA, buckets, topic, sub, Firestore, firewall
.\10-build-and-upload-jars.ps1                    # mvn package + upload JARs
.\20-create-templates-and-migs.ps1                # templates + MIGs (size 1 each)
.\30-deploy-functions.ps1                         # Lookup HTTP function + optional logger
```

If PowerShell refuses to run `.ps1` files, run once per session:
```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

### bash (Linux / macOS / Git Bash)

```bash
cd deploy
cp env.sample.sh env.sh && $EDITOR env.sh
bash 00-bootstrap.sh
bash 10-build-and-upload-jars.sh
bash 20-create-templates-and-migs.sh
bash 30-deploy-functions.sh
```

### Run the client locally

```powershell
# PowerShell
$LOOKUP_URL = gcloud functions describe lookup --region=$env:REGION --format='value(serviceConfig.uri)'
java -jar ..\client\target\cn2026-labels-client-1.0-jar-with-dependencies.jar $LOOKUP_URL
```
```bash
# bash
LOOKUP_URL=$(gcloud functions describe lookup --region=$REGION --format='value(serviceConfig.uri)')
java -jar ../client/target/cn2026-labels-client-1.0-jar-with-dependencies.jar "$LOOKUP_URL"
```

## Functional operations exposed by `LabelsService` (SF)

| RPC | Style | Description |
|-----|-------|-------------|
| `SubmitImage(stream ImageChunk) -> SubmitResponse` | client streaming | uploads an image, returns `requestId` |
| `GetResult(RequestId) -> LabelResult` | unary | labels (EN+PT), score, processedAt |
| `QueryByLabel(LabelDateRange) -> FileNames` | unary | filenames matching a label between two dates |
| `DownloadImage(RequestId) -> stream ImageChunk` | server streaming | optional, fetches stored image |

## Management operations exposed by `ScaleService` (SG)

| RPC | Description |
|-----|-------------|
| `ScaleGrpcServers(ScaleRequest)` | resize the gRPC server MIG |
| `ScaleLabelsApp(ScaleRequest)`   | resize the Labels-App MIG |
| `GetStatus(Empty)`               | current target sizes + running instance names |

## Data layout

- **Cloud Storage** bucket `cn2026-g06-images`: one blob per submitted image, name = `requestId`.
- **Pub/Sub** topic `labels-tasks`, shared subscription `labels-tasks-sub` (work-queue pattern).
- **Firestore** collection `labels-results`, doc id = `requestId`:
  ```jsonc
  {
    "requestId":   "9b9c...",
    "filename":    "cat.jpg",
    "bucket":      "cn2026-g06-images",
    "blob":        "9b9c...",
    "submittedAt": Timestamp,         // set by the gRPC server on SubmitImage
    "processedAt": Timestamp,         // set by the worker when DONE
    "status":      "PENDING|DONE",
    "labels":      [{"labelEn":"Cat","labelPt":"Gato","score":0.98}, ...],
    "labelsIndex": ["cat","gato",...] // lowercase, indexed for whereArrayContains
  }
  ```
  Optional collection `requests-log` (filled by `pubsub-logger`) keeps an audit trail.

## Demo script

```text
# (terminal A)
java -jar client/target/cn2026-labels-client-1.0-jar-with-dependencies.jar "$LOOKUP_URL"
# 7 -> Show MIG status (1 grpc, 1 worker)
# 6 -> Scale Labels-App to 3
# 7 -> Show MIG status (now 3 workers spinning up)
# 1 -> Submit ../Project/vision-translation/cat.jpg
# 1 -> Submit ../Project/vision-translation/img-02.jpg
# 2 -> Get result for each requestId
# 3 -> Query files with label "cat" with no date bounds
# 4 -> Download image for one requestId
# 6 -> Scale Labels-App back to 1
```

## Assumptions and points of failure

See [`report/REPORT.md`](report/REPORT.md).
