# CN2026Labels - Final Report

> ISEL - LEIRT / LEIC / LEIM - Computação na Nuvem (verão 2025/2026)
> Grupo G06
> _Skeleton; fill in author names, project id, screenshots and demo timings before submission._

---

## 1. Objectives and scope

CN2026Labels is an elastic, cloud-native pipeline for detecting **labels** in images and **translating** them from English to Portuguese.  It must:

- accept image uploads from a console client via gRPC,
- store them in Cloud Storage,
- process them asynchronously through a worker pool that calls Vision and Translation APIs,
- persist the results in Firestore so they can be queried later,
- **scale horizontally** the gRPC servers and the worker pool on demand.

This report describes the architecture, the contracts between components, the data formats, the implementation decisions and the failure modes we considered.

## 2. Architecture overview

The system is decomposed into five long-lived components and one ad-hoc client (Figure 1 of the enunciado).

| Component | Service | Tech | Role |
|-----------|---------|------|------|
| Lookup    | Cloud Run Functions, HTTP | Node.js 20 + Compute Engine SDK | Returns the public IPs of the gRPC MIG members |
| gRPC Server | Compute Engine MIG | Java 17, gRPC 1.79 | Receives requests, stores blob, publishes tasks, serves queries |
| Pub/Sub   | Pub/Sub | -- | Decouples gRPC server (publisher) from workers (consumers) |
| Labels App | Compute Engine MIG | Java 17 | Pulls tasks, calls Vision and Translate, writes Firestore |
| Firestore | Firestore (Native) | -- | Holds `labels-results` (and optional `requests-log`) |
| pubsub-logger (optional) | Cloud Run Functions, Pub/Sub | Node.js 20 | Audit trail of every task |
| Cloud Storage | -- | -- | Raw image blobs (bucket `cn2026-g06-images`) |

We chose **two distinct gRPC services** in the same server process to keep the contract clean:

- `LabelsService` for **SF** (functional operations submitted by end-users).
- `ScaleService`  for **SG** (management operations used to demonstrate elasticity).

Both services share the same `Server` instance so they listen on the same TCP port.

## 3. Protobuf contract

Excerpt of the contract (full source in `grpcContract/src/main/proto/CN2026Labels.proto`):

```proto
service LabelsService {
  rpc SubmitImage(stream ImageChunk) returns (SubmitResponse);
  rpc GetResult  (RequestId)         returns (LabelResult);
  rpc QueryByLabel(LabelDateRange)   returns (FileNames);
  rpc DownloadImage(RequestId)       returns (stream ImageChunk);
}

service ScaleService {
  rpc ScaleGrpcServers(ScaleRequest)        returns (ScaleResponse);
  rpc ScaleLabelsApp  (ScaleRequest)        returns (ScaleResponse);
  rpc GetStatus       (google.protobuf.Empty) returns (StatusResponse);
}
```

**Design choices and rationale**

- `SubmitImage` is **client-streaming**: the client splits the image into 64 KiB chunks. The server writes them directly to a Cloud Storage `WriteChannel`, never accumulating the full image in memory. This is the same pattern that GCP libraries use for resumable uploads.
- `DownloadImage` is **server-streaming** for the same reason.
- We expose `LabelEntry { label_en, label_pt, score }`, not three parallel arrays. This makes the protocol self-describing and immune to mis-ordering.
- `LabelDateRange.start/end_epoch_millis` uses `int64` epoch ms instead of `google.protobuf.Timestamp` to avoid pulling an extra proto dependency and to keep parsing trivial on the client.
- `Empty` is used for `GetStatus` to signal "no input parameters".

## 4. Sequence of a successful submission

The sequence numbers below match the enunciado's Figure 1.

1. The console client calls the **Lookup function** (1) and receives a JSON list of gRPC server IPs (2).
2. The client opens a gRPC channel to one IP and sends `SubmitImage` chunks.
3. The server pipes the chunks to **Cloud Storage** as a single blob whose name is the `requestId` (3.1). It then seeds a `PENDING` Firestore doc and publishes a JSON task `{requestId, bucket, blob, filename}` to **Pub/Sub** (3.2). The reply carries the `requestId`.
4. The optional **pubsub-logger** function writes `requests-log/{requestId}` (4.0 -> 5).
5. One **Labels-App** worker receives the task on the shared subscription (4.1), reads the image from Storage (4.2), calls **Vision** (LABEL_DETECTION) and **Translate** EN -> PT (6) and writes a merged `DONE` doc with labels + lower-cased index in Firestore (7).
6. Later, the client calls `GetResult` or `QueryByLabel`; the server reads Firestore (8) and replies.

## 5. Data formats

### 5.1 Pub/Sub task message

```json
{ "requestId": "...", "bucket": "...", "blob": "...", "filename": "..." }
```

Attributes also carry `requestId` so that filters (`pubsub-logger`, monitoring) can read it without parsing JSON.

### 5.2 Firestore document (`labels-results/{requestId}`)

| Field         | Type      | Set by   | Notes |
|---------------|-----------|----------|-------|
| `requestId`   | string    | server   | document id duplicate, useful for queries |
| `filename`    | string    | server   | original client-supplied filename |
| `bucket`      | string    | server   | Cloud Storage bucket |
| `blob`        | string    | server   | blob name in that bucket |
| `submittedAt` | Timestamp | server   | server-side timestamp at submission |
| `processedAt` | Timestamp | worker   | server-side timestamp at completion |
| `status`      | string    | both     | `PENDING` then `DONE` |
| `labels`      | array     | worker   | `[ {labelEn,labelPt,score}, ... ]` |
| `labelsIndex` | array     | worker   | lower-cased flat list of all labels (en + pt) for `whereArrayContains` |

### 5.3 Why a `labelsIndex` field?

Firestore allows only one `array_contains` per query and is **case-sensitive**.  Storing a denormalised lower-cased index lets us answer `QueryByLabel("Cat" or "cat" or "gato")` with a single index lookup, then we filter the date range in the server.  Trade-off: a small amount of duplicated data.

## 6. Elasticity (SG)

Both MIGs are stateless: each VM downloads its JAR from a configuration bucket at startup, reads its environment from instance metadata (set by `gcloud compute instance-templates create --metadata=...`), and exposes either gRPC or the Pub/Sub subscriber.  Scaling is therefore reduced to one call:

```java
client.resizeAsync(projectId, zone, migName, targetSize).get();
```

We deliberately do **not** use autoscaling for the demo, so we can prove control of elasticity via `ScaleService`.  Adding HTTP-load-based autoscaling later is one line.

## 7. Assumptions

- Each VM uses **Application Default Credentials** through a dedicated service account (`cn2026-runtime`) with: `storage.objectAdmin`, `pubsub.publisher`, `pubsub.subscriber`, `datastore.user`, `cloudtranslate.user`, `serviceusage.serviceUsageConsumer`, `compute.instanceAdmin.v1`.
- gRPC channels are plaintext (port 8000). In production we would terminate TLS at an HTTPS load balancer; the simpler scheme matches what the labs use.
- The image is a JPG / PNG smaller than the Pub/Sub message limit only at the **task message** level; the image itself lives in Cloud Storage and is never embedded.
- `requestId` is a UUID generated by the server, so collisions are negligible and document writes are **idempotent** under at-least-once delivery.
- The Lookup function returns only `RUNNING` instances. A newly-created VM may appear before its gRPC port is listening; the client retries against another IP in that case (see `ClientApp.connect()`).

## 8. Comparison with alternative designs

| Decision | Chosen | Alternative | Why |
|----------|--------|-------------|-----|
| Image transfer | gRPC client streaming | One unary RPC with raw bytes | bounded memory, also matches LAB2 streaming exercises |
| Worker -> Storage | Direct download via `gs://` URI in Vision | Worker reads bytes itself | one fewer hop, Vision accepts GCS URIs natively |
| Result query | Server reads Firestore | Client reads Firestore | keeps service accounts and credentials inside the cluster |
| MIG sizing | Manual scaling through SG | Autoscaler (CPU based) | required for the demo of operations 'B' |
| Cloud Functions | Node.js | Java | smaller cold start, lighter dependency list; the rest of the system already uses Java |

## 9. Points of failure / mitigations

| Failure | Detection | Mitigation |
|---------|-----------|------------|
| Vision quota exhausted | exception bubbling up from `batchAnnotateImages` | worker calls `nack()` -> Pub/Sub redelivery after back-off; we filter by `minScore` to reduce label count |
| Worker crash mid-message | ack-deadline expires | another worker (or the same one after restart) picks the message up; idempotent Firestore write |
| Translate API hiccup | exception caught in `translateLabel` | label stored with `labelPt = labelEn` instead of failing the whole task |
| Storage write fails | exception inside `onNext` | client receives an `onError`; nothing is published to Pub/Sub, so Firestore stays consistent |
| MIG VM not yet listening | gRPC channel returns UNAVAILABLE | client re-lookups (menu option 8) and tries another IP |
| Pub/Sub duplicate delivery | message ID seen twice | document write is `set(...,MERGE)` keyed by stable `requestId` -> idempotent |
| gRPC server reads stale Firestore | none (eventual consistency window <1s) | `GetResult` returns `completed=false` so the client can poll |

## 10. Objectives reached / not reached

> _Fill in based on the demo._

- [x] Stream image upload through gRPC
- [x] Pub/Sub work-queue with shared subscription
- [x] Vision LABEL_DETECTION + Translate EN -> PT
- [x] Firestore persistence with composite query (`labelsIndex` + date range)
- [x] Lookup function returns running MIG IPs (no hard-coded endpoints on the client)
- [x] `ScaleService` resizes both MIGs from the client menu
- [x] Optional Pub/Sub logger function
- [ ] HTTPS / mTLS for the gRPC channel (deferred)
- [ ] Autoscaling rules (manual scaling only, by design)

## 11. References

- Enunciado, *Computação na Nuvem - Trabalho final*, v1.0, 4 May 2026.
- Lab 2 (gRPC), Lab 3 (Storage), Lab 4 (Firestore), Lab 5 (Pub/Sub + Firestore), Lab 6 (MIG).
- Google Cloud Vision - Detect Labels: https://cloud.google.com/vision/docs/labels
- Google Cloud Translate (Java sample): https://github.com/googleapis/google-cloud-java/blob/main/google-cloud-examples/...
