# CN2026Labels

Elastic distributed image-labeling system built on Google Cloud Platform.  
Final project for the Cloud Computing course at ISEL (2025/2026).

## What it does

A user submits an image via a gRPC client. The system stores it, sends it through an async pipeline, runs Google Vision AI to detect objects, translates the detected labels to Portuguese, and persists everything in Firestore. All tiers scale independently via Managed Instance Groups.

## Architecture

```
  Client (laptop)
     │
     │  (1) HTTP  ──► Cloud Function (Lookup)
     │               returns IPs of running gRPC VMs
     │
     │  (2) gRPC SubmitImage (client-streaming)
     ▼
  ┌──────────────────────────────┐
  │  gRPC Server  (Compute MIG)  │  ← scales via ScaleService RPC
  │  LabelsService + ScaleService│
  └──────────┬───────────────────┘
             │ (3) publish task
             ▼
         Pub/Sub topic
         (work-queue)
             │
             ▼
  ┌──────────────────────────────┐
  │  Labels-App   (Compute MIG)  │  ← scales independently
  │  Pub/Sub subscriber          │
  └──┬─────────────┬─────────────┘
     │             │
     │ Vision API  │ Translation API
     │ (detect)    │ (EN → PT)
     │             │
     └──────┬──────┘
            ▼
        Firestore  ◄── queried by gRPC Server on GetResult / QueryByLabel
        Cloud Storage  (raw image blobs)
```

## Tech stack

| Layer | Technology |
|-------|-----------|
| RPC framework | gRPC + Protocol Buffers (proto3) |
| Language | Java 17, Node.js 20 |
| Build | Maven (fat JARs via maven-shade-plugin) |
| Compute | Compute Engine Managed Instance Groups |
| Messaging | Cloud Pub/Sub (shared subscription work-queue) |
| Storage | Cloud Storage (blobs) + Firestore (metadata + results) |
| AI | Vision API (label detection) + Translation API (EN→PT) |
| Service discovery | Cloud Run Function (HTTP, Node.js) |
| Deploy | PowerShell scripts (IaC) |

## gRPC API surface

### LabelsService (functional)

| RPC | Pattern | Description |
|-----|---------|-------------|
| `SubmitImage(stream ImageChunk)` | client-streaming | upload image in chunks, returns `requestId` |
| `GetResult(RequestId)` | unary | fetch labels (EN + PT) and processing status |
| `QueryByLabel(LabelDateRange)` | unary | filenames matching a label between two dates |
| `DownloadImage(RequestId)` | server-streaming | retrieve stored image in chunks |

### ScaleService (management)

| RPC | Description |
|-----|-------------|
| `ScaleGrpcServers(n)` | resize the gRPC server MIG |
| `ScaleLabelsApp(n)` | resize the worker MIG |
| `GetStatus()` | current MIG target sizes + running instance names |

## Data model (Firestore)

```jsonc
// Collection: labels-results  — doc id = requestId (UUID)
{
  "requestId":   "9b9c1e4e-...",
  "filename":    "cat.jpg",
  "status":      "PENDING | DONE",
  "submittedAt": Timestamp,
  "processedAt": Timestamp,
  "labels": [
    { "labelEn": "Cat",      "labelPt": "Gato",    "score": 0.98 },
    { "labelEn": "Whiskers", "labelPt": "Bigodes",  "score": 0.91 }
  ],
  // lowercase EN+PT — enables whereArrayContains queries in both languages
  "labelsIndex": ["cat", "gato", "whiskers", "bigodes"]
}
```

## Project layout

```
Project/CN2026Labels/
├── grpcContract/     # Protobuf definitions (shared by server + client)
├── grpcServer/       # gRPC server: SF + SG, Pub/Sub publisher, Firestore reader
├── labelsApp/        # Worker: Pub/Sub consumer, Vision + Translation, Firestore writer
├── client/           # Interactive console client (9 operations)
├── cloudFunctions/
│   ├── lookup/       # HTTP function — returns IPs of running gRPC VMs
│   └── pubsubLogger/ # Optional: audit trail function
├── deploy/           # PowerShell IaC scripts (bootstrap → build → MIGs → functions)
└── report/           # Final evaluation report (Markdown + LaTeX)
```

## Quick deploy (PowerShell)

```powershell
cd Project/CN2026Labels/deploy
Copy-Item env.sample.ps1 env.ps1   # fill in your GCP project ID and region
.\00-bootstrap.ps1                 # enable APIs, create SA, buckets, Pub/Sub, Firestore, firewall
.\10-build-and-upload-jars.ps1     # mvn package + upload JARs to config bucket
.\20-create-templates-and-migs.ps1 # VM templates + MIGs (1 instance each)
.\30-deploy-functions.ps1          # Lookup + Logger Cloud Functions (prints the Lookup URL)
```

```powershell
# Run the client
$URL = gcloud functions describe lookup --region=$env:REGION --format='value(serviceConfig.uri)'
java -jar Project/CN2026Labels/client/target/cn2026-labels-client-1.0-jar-with-dependencies.jar $URL
```

To tear everything down:
```powershell
.\99-teardown.ps1
```

## Key design decisions

**Decoupling via Pub/Sub** — the gRPC server never calls Vision or Translation directly. It publishes a task message and returns the `requestId` immediately. Workers consume async, survive restarts, and retry automatically via Pub/Sub ack/nack semantics.

**Independent elasticity** — gRPC servers and workers are in separate MIGs and can be resized independently through the `ScaleService` RPC. This lets the system absorb burst submissions without over-provisioning the AI-calling worker tier.

**Idempotent writes** — Firestore uses the `requestId` as document ID and Firestore `MERGE` semantics, so duplicate Pub/Sub deliveries never corrupt state.

**Dependency alignment** — all Google Cloud client libraries are pinned via the `google-cloud-bom` BOM (v26.65.0) and gRPC stubs are merged with `ServicesResourceTransformer` in maven-shade to avoid `NoClassDefFoundError` on the DNS resolver SPI.

See [`Project/CN2026Labels/CHANGES.md`](Project/CN2026Labels/CHANGES.md) for the full engineering rationale.

## License

Academic project — ISEL, Computação na Nuvem 2025/2026.
