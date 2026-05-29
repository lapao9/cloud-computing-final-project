# CN2026Labels — Presentation Guide (15 min, 4 students)

Built from the teacher's *Recomendações para a demonstração do trabalho final
de CN 2026* (José Simão / Carlos Júnior, 14 May 2026). This document is a
**runbook + speaker script + Q&A defense kit** for the live oral defence.

> **Hard rule from the teacher:** maximum **15 min**, only **IntelliJ** to run
> the client, demo must walk through points (a)–(h) of §3 of the PDF.

---

## 0. The 30-second elevator pitch

> *CN2026Labels is an elastic image-labelling pipeline on GCP. A client app
> uploads images over gRPC to a server farm running in a Managed Instance
> Group. Each server stores the raw image in Cloud Storage, writes a `PENDING`
> document to Firestore, and publishes a task to a Pub/Sub topic. A
> second Managed Instance Group of workers — the `LabelsApp` — subscribes to
> that topic; each worker pulls a task, calls the Vision API to detect labels,
> the Translation API to render them in Portuguese, and writes a `DONE`
> document back to Firestore. Both MIGs are dynamically scalable from the
> client through a separate gRPC management service.*

Every architectural choice traces back to one of three goals from the
*enunciado*:

| Goal              | Choice                                                                                              |
| ----------------- | --------------------------------------------------------------------------------------------------- |
| **Elasticity**    | Both layers run inside MIGs whose size can be changed at runtime via the SG service.                |
| **Decoupling**    | The gRPC server *never* calls Vision/Translate directly — the Pub/Sub queue absorbs back-pressure. |
| **Discoverability** | Clients call an HTTP Cloud Function to learn the IPs of *all* running gRPC servers and choose one. |

---

## 1. Role assignment for the 15-minute presentation

Tune to your team's preferences but **practice it once** before the day.

| Student | Owns                                                                             | Approx. time |
| ------- | -------------------------------------------------------------------------------- | ------------ |
| **A — Architect**       | Slides 1–3: architecture, gRPC contract, Pub/Sub format, Firestore schema | 4 min        |
| **B — Console driver**  | GCP console screen-sharing: Pub/Sub backlog, Firestore docs, MIG resizing        | 3 min        |
| **C — Client driver**   | Drives the IntelliJ client; everyone submits one image                           | 5 min        |
| **D — Queries & wrap-up** | Runs `QueryByLabel`, `DownloadImage`, scaling tests, closes with the architecture recap | 3 min        |

All four students **must submit at least one image** during step 3(d) of the
demo (that requirement is in the PDF).

---

## 2. Pre-flight checklist (do this 15 min before the presentation)

### 2.1 Clean state — required by the PDF (§3.a)

```powershell
cd D:\ISEL\CN\CN_G06\Project\CN2026Labels\deploy
. .\env.ps1

# Wipe Firestore "labels-results" collection
gcloud firestore documents delete `
  $(gcloud firestore documents list --collection-id=$env:FIRESTORE_COL --limit=500 --format='value(name)') --quiet 2>$null

# Wipe the images bucket
gcloud storage rm "gs://$($env:BUCKET)/**" --quiet 2>$null

# Drain any leftover Pub/Sub messages
gcloud pubsub subscriptions seek $env:SUBSCRIPTION --time=(Get-Date).ToString("o")
```

> **Why we drain the subscription:** Pub/Sub keeps un-acked messages for up to
> 7 days. If we don't seek to "now", the moment a worker comes online during
> the demo it will process stale tasks from previous sessions and the new
> Firestore docs won't match what we submitted live.

### 2.2 Set the starting topology — also required (§3.c)

```powershell
gcloud compute instance-groups managed resize grpc-server-mig --zone=$env:ZONE --size=1
gcloud compute instance-groups managed resize labels-app-mig  --zone=$env:ZONE --size=0
```

Wait until:

```powershell
gcloud compute instance-groups managed list-instances grpc-server-mig --zone=$env:ZONE
# expect exactly 1 RUNNING instance
gcloud compute instance-groups managed list-instances labels-app-mig --zone=$env:ZONE
# expect (empty)
```

Confirm the gRPC server inside that 1 VM has finished booting:

```powershell
$grpcVm = gcloud compute instance-groups managed list-instances grpc-server-mig --zone=$env:ZONE --format="value(NAME)"
gcloud compute ssh $grpcVm --zone=$env:ZONE --command="sudo tail -3 /var/log/cn2026-startup.log"
# last line must be "[gRPC] server started on port 8000"
```

### 2.3 Open IntelliJ with a single Run Configuration

The teacher allows **only IntelliJ** to launch the client. Create one Run
Configuration per teammate so each can launch their own client instance
quickly:

1. `File → Open` the parent pom: `D:\ISEL\CN\CN_G06\Project\CN2026Labels\pom.xml`.
2. Wait for Maven import to complete.
3. `Run → Edit Configurations… → +Application`:
   - **Name**: `Client (Martim)` (repeat with names of other team members)
   - **Module**: `cn2026-labels-client`
   - **Main class**: `cn2026.client.ClientApp`
   - **Program arguments**: the Lookup URL, e.g.
     `https://lookup-7e5h4zcgvq-ew.a.run.app`
   - **Working directory**: project root
4. Save. Repeat 4× so every teammate has a profile.

If IntelliJ runs Maven Build before launch, that's fine. Don't `mvn package`
ahead of time — let IntelliJ handle the classpath.

### 2.4 Browser tabs to keep ready (Console driver — student B)

Pre-open these tabs and **log in to GCP** so no auth prompt happens mid-demo:

1. **Cloud Storage** → bucket `final_project_2526g6` (object browser)
2. **Pub/Sub** → topic `labels-tasks` → subscription `labels-tasks-sub`
   (so you can show "unacked messages" count and pull preview)
3. **Firestore** → collection `labels-results` (so the empty state is visible)
4. **Compute Engine** → "Instance Groups" page (to show MIG sizes live)
5. **Cloud Functions** → `lookup` (to show the deployed function — optional)

### 2.5 Image pack on every machine

Each teammate has their own visually different image (richer demo per §3.b
— "Utilize na demonstração imagens diversas"):

- Student A — `cat.jpg` (already in `Project/vision-translation/`)
- Student B — `img-02.jpg` (already there)
- Student C — find a picture of a *car* on Google before the demo, save to
  Desktop as `car.jpg`
- Student D — find a picture of a *beach* or *food*, save as `beach.jpg`

Doing it this way means `QueryByLabel("cat")` will return exactly one
filename — easy to verify on stage.

---

## 3. Section 1 — Architectural intro (~4 min, Student A speaks)

Three slides are enough. Put them in this order; the PDF asks for exactly
these three topics before any demo.

### 3.1 Slide 1 — System overview (30 s)

Show a diagram with these boxes and arrows (adapt from
[README.md](README.md#L18-L54)). Talking points:

> *"The client never speaks to a hardcoded IP. It calls a Cloud Function
> over HTTP to discover the IPs of running gRPC servers. The user picks one
> and opens a gRPC channel. Submissions go through a stream: the gRPC server
> writes the bytes to Cloud Storage, seeds a Firestore document in
> `PENDING` state, and publishes a Pub/Sub task. Workers in the Labels-App
> MIG consume from a shared subscription — that's a work-queue pattern, so
> each task goes to exactly one worker. The worker calls Vision and
> Translate and updates the Firestore doc to `DONE`."*

### 3.2 Slide 2 — Service contract `LabelsService` (SF) and `ScaleService` (SG) — *PDF §1.a*

Show the proto file or a summarised table.

#### `LabelsService` — the functional service

| RPC                                       | Style           | Semantics                                                                                                              |
| ----------------------------------------- | --------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `SubmitImage(stream ImageChunk) → SubmitResponse` | client-streaming | Client streams the image in chunks. First chunk carries the filename. Server stores the blob, seeds Firestore, publishes Pub/Sub task. Reply carries the **requestId** (a UUID) which is the *only* identifier the client needs from then on. |
| `GetResult(RequestId) → LabelResult`              | unary            | Reads the Firestore doc. Reply has `completed=false` while the worker hasn't finished, `true` once `status=DONE`. Returns labels with EN/PT and a confidence score 0..1. |
| `QueryByLabel(LabelDateRange) → FileNames`        | unary            | Server scans Firestore via `whereArrayContains("labelsIndex", lower(label))` and applies the date range in `processedAt`. Returns the matching filenames. |
| `DownloadImage(RequestId) → stream ImageChunk`    | server-streaming | Streams the stored blob back in 64 KB chunks. Used in the demo to *prove* that what the worker labelled is actually the image we uploaded. |

#### `ScaleService` — the management service

| RPC                                  | Effect                                                                              |
| ------------------------------------ | ----------------------------------------------------------------------------------- |
| `ScaleGrpcServers(ScaleRequest)`     | Calls `InstanceGroupManagersClient.resize` against `grpc-server-mig`.               |
| `ScaleLabelsApp(ScaleRequest)`       | Same for `labels-app-mig`.                                                          |
| `GetStatus(google.protobuf.Empty) → StatusResponse` | Returns the **target size** (the requested number) and the **names of running VMs** for both MIGs. |

Talking points:

> *"We split functional from management so that the management service can
> evolve without changing the data path. Both live in the same server JVM
> for simplicity but use distinct proto services — the contract is clean."*

### 3.3 Slide 3 — Pub/Sub message format — *PDF §1.b*

Show one bullet list and the raw JSON example:

- **Topic**: `labels-tasks`
- **Subscription**: `labels-tasks-sub` — single shared subscription, so the
  workers compete for tasks (**work-queue pattern**).
- **Encoding**: JSON in `PubsubMessage.data` (UTF-8).
- **Attributes**: `requestId` is also set as a Pub/Sub *attribute* so the
  optional `pubsub-logger` Cloud Function can filter without parsing JSON.

Example payload (matches [LabelsServiceImpl:115-124](grpcServer/src/main/java/cn2026/server/LabelsServiceImpl.java#L115-L124)):

```json
{
  "requestId": "9b9c1e4e-2b39-4e02-8e1f-d6e0f3f5a112",
  "bucket":    "final_project_2526g6",
  "blob":      "9b9c1e4e-2b39-4e02-8e1f-d6e0f3f5a112",
  "filename":  "cat.jpg"
}
```

Talking points:

> *"The message is intentionally minimal — it carries pointers, not data.
> The image itself stays in Cloud Storage. This keeps Pub/Sub payloads
> small (well under the 10 MB Pub/Sub limit), and it means a redelivery
> on worker crash is harmless: the worker just re-reads the same blob."*

> *"Acknowledgement: the worker acks **only after** the Firestore write
> succeeds. If Vision/Translate or Firestore fails, the worker nacks and
> Pub/Sub redelivers. Because the Firestore document id is the stable
> requestId, the duplicate write is idempotent — same result, same doc."*

### 3.4 Slide 4 — Firestore schema — *PDF §1.c*

- **Collection**: `labels-results`
- **Document id**: `requestId` (UUID v4)
- **Lifecycle**: server creates with `status=PENDING`; worker merges
  `status=DONE` + results.

```jsonc
// Created by the gRPC server in SubmitImage.onCompleted()
{
  "requestId":   "9b9c1e4e-2b39-4e02-8e1f-d6e0f3f5a112",
  "filename":    "cat.jpg",
  "bucket":      "final_project_2526g6",
  "blob":        "9b9c1e4e-2b39-4e02-8e1f-d6e0f3f5a112",
  "submittedAt": <serverTimestamp>,
  "status":      "PENDING"
}

// Merged by the Labels-App worker after Vision + Translate
{
  "status":      "DONE",
  "processedAt": <serverTimestamp>,
  "labels": [
    { "labelEn": "Cat",      "labelPt": "Gato",     "score": 0.98 },
    { "labelEn": "Whiskers", "labelPt": "Bigodes",  "score": 0.91 }
  ],
  "labelsIndex": ["cat","gato","whiskers","bigodes"]
}
```

Why `labelsIndex` exists:

> *"Firestore can do a single `array-contains` per query. We pre-compute a
> lowercase array of EN+PT labels in one field so the same `QueryByLabel`
> call works whether the user asks for 'cat' or 'gato'. The date filter
> is applied in the server after Firestore returns the array-contains
> matches — Firestore doesn't allow range filters on a different field
> in the same query without composite indexes."*

This is also a great talking point about **assumptions and limitations**
(which the report needs to discuss): if the dataset grows large, we would
need a Firestore **composite index** on `(labelsIndex, processedAt)` or
move to BigQuery. For the project scale, single-field is sufficient.

---

## 4. Section 2 — Live demonstration (~10 min)

Walk through the eight points of §3 of the PDF, **in order**. Below: who does
what, and the exact commands/clicks.

### 4.1 §3.a — Show the empty starting state (30 s, Student B)

Share the GCP browser tab. Click through:

1. **Cloud Storage** → `final_project_2526g6` → "No objects". Say:
   > *"The image bucket is empty."*
2. **Firestore** → `labels-results` collection → "0 documents". Say:
   > *"And the Firestore collection has no documents."*
3. **Pub/Sub** → subscription `labels-tasks-sub` → click **Pull** → "No messages". Say:
   > *"And the work queue is empty too."*

### 4.2 §3.c — Show the starting topology (30 s, Student B)

Switch to **Compute Engine → Instance Groups**:

> *"We start with **1 gRPC server VM** and **0 Labels-App workers**, as
> requested. This is deliberate: it lets us submit images first and show
> Pub/Sub holding the work, then scale workers up."*

Verify both MIG sizes on screen (`grpc-server-mig` target=1, running=1;
`labels-app-mig` target=0).

### 4.3 §3.d — All four students submit images (~2 min)

#### Student C: explain the discovery first (30 s)

In IntelliJ, run any one client config. The startup output will read:

```
[Lookup] HTTP 200
Available gRPC servers:
  1) grpc-XXXX  <ip>:8000
Pick one (0 = auto-first):
```

Say:

> *"The client first calls the lookup Cloud Function. It returns the list
> of running gRPC servers — right now there's exactly one. The user can
> pick which server to talk to; we pick that one server."*

Pick `1` (or `0`). The menu prints.

#### All four students submit (~1.5 min)

Each teammate:

1. Opens **their own** Run Configuration in IntelliJ → Run.
2. Picks the server.
3. Chooses menu option **`1` — Submit image**.
4. Pastes their image path:
   - Student A: `D:\ISEL\CN\CN_G06\Project\vision-translation\cat.jpg`
   - Student B: `D:\ISEL\CN\CN_G06\Project\vision-translation\img-02.jpg`
   - Student C: `C:\Users\<you>\Desktop\car.jpg`
   - Student D: `C:\Users\<you>\Desktop\beach.jpg`
5. Says aloud the `requestId` it returns (or pastes it in the Teams chat).

#### Student B: show backlog in GCP console (45 s)

Switch back to the **Pub/Sub subscription** tab → click **Pull**.

You should see **4 messages**, each with a JSON `data` field carrying a
distinct `requestId`. Don't ack them (leave them re-deliverable). Say:

> *"Here are the four tasks in the work queue. The Vision/Translate APIs
> have not been called yet — there are no workers. This is exactly the
> decoupling we wanted: the gRPC server is not blocked by AI processing."*

Then switch to **Firestore** → `labels-results`:

> *"We have **four documents** in Firestore, all with `status: PENDING`.
> No labels yet. The bucket has the 4 raw images — show Cloud Storage tab."*

This satisfies the PDF: "mostrar também que essas mensagens não existem no
Firestore" — strictly speaking the PDF wants "no Firestore docs", but we
seed PENDING docs so that `GetResult` doesn't return `NOT_FOUND` for the
user during the brief processing window. **Be ready to explain this to the
teacher** — see Q&A §7.

### 4.4 §3.e — Scale Labels-App to 2 (~30 s, Student D)

In IntelliJ (any one client), menu option **`6` — Scale Labels-App** →
enter **`2`**.

Client prints:

```
[Scale] labels-app-mig -> 2 (currentSize=2)
```

Then immediately option **`7` — Show MIG status** to confirm.

Switch to the **Compute Engine → Instance Groups** tab. The `labels-app-mig`
row shows "2 / 2" with two new VMs going `PROVISIONING → STAGING → RUNNING`.

Say:

> *"We invoked `ScaleLabelsApp(target=2)` over the management service.
> Internally that calls `InstanceGroupManagersClient.resize`. Two new VMs
> are now booting. Each will install Java, download the worker JAR from
> the config bucket, and start consuming from the shared subscription."*

> **Time budget tip:** The VMs need ~90 seconds to fully boot before they
> start processing. Use this dead time to talk through the architecture
> slide briefly, or open the **subscription** tab and refresh — the
> "unacked messages" counter will drop as workers consume.

### 4.5 §3.f — Show DONE documents in Firestore (~1 min, Student B)

Refresh the **Firestore** tab. Each of the 4 documents now has:

- `status: "DONE"`
- `processedAt: <timestamp>`
- `labels: [...]` array
- `labelsIndex: [...]` lowercase array

Click into one (say the `cat.jpg` doc) and **expand** the `labels` array:

> *"Vision identified, for example, 'Cat', 'Whiskers', 'Felidae' with
> confidence scores. Translation gave us the Portuguese equivalent. The
> `labelsIndex` field is what `QueryByLabel` searches against."*

Also visit **Cloud Storage** → confirm 4 image blobs (each named after a
`requestId`). Mention the bucket metadata records the original filename
(see [LabelsServiceImpl:70-74](grpcServer/src/main/java/cn2026/server/LabelsServiceImpl.java#L70-L74)).

### 4.6 §3.g — Scale gRPC servers to 2, show client-side multi-server (~2 min, Student D)

In IntelliJ (any one client), menu option **`5` — Scale gRPC server MIG** →
enter **`2`**.

The client prints `[Scale] grpc-server-mig -> 2`.

Wait ~90s for the new VM to come up. Then a different teammate runs
*their* Run Configuration. On startup the client now prints:

```
Available gRPC servers:
  1) grpc-XXXX  <ip-A>:8000
  2) grpc-YYYY  <ip-B>:8000
Pick one (0 = auto-first):
```

Pick **`2`** (the *second* server). Submit one more image (everyone needs
to show one submission going through the *new* server).

Switch to the original client (already connected to server #1). Re-run
menu option `7` (status). It still works — the user's existing channel is
to server #1; the new server only handles new connections.

Say:

> *"Both gRPC servers are stateless with respect to client identity. State
> lives in Firestore and Cloud Storage. A client can talk to any server
> for any `requestId`. We demonstrated picking a specific server from the
> lookup result, satisfying §3.g of the recommendations."*

### 4.7 §3.h — Query functionality (~2 min, Student D)

Run the following on one client:

1. **Menu `2` — Get result by requestId** → paste the `requestId` Student
   A got for `cat.jpg`. The client prints the labels in EN + PT with
   scores.

2. **Menu `3` — Query by label** → label = `cat` (or `gato`), start = 0,
   end = 0. Client returns `cat.jpg`. Then try `gato` — same result, proving
   the bilingual `labelsIndex`.

3. **Menu `4` — Download image** → paste Student C's `car.jpg`
   `requestId`. Client streams the blob back and (depending on
   implementation) saves it locally. Mention:

   > *"The download verifies that what we labelled is actually the image
   > we uploaded — useful to defend that no images are mixed up across
   > requestIds."*

### 4.8 Close strong — recap in ~30 s (Student D or A)

> *"Recapping: we provisioned everything via PowerShell scripts (APIs, IAM,
> buckets, Pub/Sub topic and subscription, Firestore, firewall, two MIG
> templates, two MIGs, and two Cloud Functions). The client found servers
> through the lookup function, submitted images via a streaming gRPC call,
> and saw them processed asynchronously by a worker tier we scaled live.
> All metadata lives in Firestore — the system survives any combination of
> VM restarts because no application state is on the VMs themselves."*

---

## 5. What to keep open *during* the entire demo

In Teams screen share, arrange the following so transitions are click-only:

```
+---------------------------------------------+--------------------------+
|  IntelliJ (1 Run Config window per student) |  GCP Console - tabs:     |
|                                             |   - Cloud Storage bucket |
|                                             |   - Firestore collection |
|                                             |   - Pub/Sub subscription |
|                                             |   - Instance Groups page |
|                                             |   - (lookup function)    |
+---------------------------------------------+--------------------------+
```

Do **not** open a terminal during the demo — it looks unprofessional and
the PDF says "só podem usar o IntelliJ para executar o cliente". Any setup
or recovery you need is done **before** the screen-share starts.

---

## 6. Backup / recovery during the demo

> Pre-print these commands on a phone or second monitor. **Use only if the
> system breaks** mid-demo, never as part of the demo itself.

| Symptom                                  | What to type in a side PowerShell window                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| gRPC server VM stops responding          | `gcloud compute instance-groups managed recreate-instances grpc-server-mig --instances=<name> --zone=europe-west1-b`                       |
| Workers stop consuming Pub/Sub backlog   | `gcloud compute instance-groups managed recreate-instances labels-app-mig --instances=<name> --zone=europe-west1-b`                        |
| Lookup function returns 500              | `gcloud functions describe lookup --region=europe-west1` → check `state: ACTIVE`. Worst case, re-deploy with `.\30-deploy-functions.ps1`.  |
| Firestore says PERMISSION_DENIED         | Service account `cn2026-runtime@...` needs `roles/datastore.user`. Re-bind with `gcloud projects add-iam-policy-binding ...`.             |
| Client can't connect                     | `Test-NetConnection -ComputerName <ip> -Port 8000`. If `False`, the JVM crashed; SSH in and tail `/var/log/cn2026-startup.log`.            |

---

## 7. Q&A — likely questions and how to answer them

The teacher will probe **architecture**, **failure modes**, and **why** you
chose each technology. Pre-formed answers:

### "Why Pub/Sub and not direct gRPC server → worker?"

> *"Three reasons. First, decoupling — the gRPC server replies to the
> client immediately after uploading the blob, without waiting for the
> Vision/Translate APIs. Second, elasticity — the queue absorbs bursts
> when there are fewer workers than producers. Third, failure isolation —
> if a worker dies mid-task, Pub/Sub redelivers the message; if we had
> direct calls the task would be lost."*

### "What if a worker crashes after calling Vision but before writing Firestore?"

> *"We `ack` Pub/Sub **only after** the Firestore write succeeds. If we
> crash before then, we never sent the ack, so Pub/Sub redelivers the
> task after `ack-deadline` (60s). Vision and Translate would be called
> again — that's billable but harmless. The Firestore write is idempotent
> because the document id is the `requestId`. We could de-duplicate Vision
> by storing intermediate state, but for project scale it's a deliberate
> trade-off."*

### "Why is there a `PENDING` Firestore document if the worker writes the result?"

> *"Two reasons. (1) If a client calls `GetResult` while the worker is
> still processing, we want to return `completed=false`, not `NOT_FOUND`.
> (2) It captures `submittedAt` independently of `processedAt` — useful if
> Vision is slow we can compute queueing latency. The trade-off is one
> extra Firestore write per submission; we judged it worth it."*

### "How does the client discover servers if all VMs are deleted between calls?"

> *"The client re-runs lookup on every startup. Within a session, if a
> connection fails, menu option **8 — Re-lookup and reconnect** re-calls
> the Cloud Function and offers the current list. The Cloud Function calls
> `listManagedInstances` and filters by `instanceStatus=RUNNING`, so it
> only returns IPs that are actually alive."*

### "Why store labels in BOTH `labels` and `labelsIndex`?"

> *"`labels` is the human-readable array with score and EN/PT pairs —
> what we return to the user. `labelsIndex` is a single lowercased flat
> array (EN + PT) for Firestore's `whereArrayContains`, which can only
> filter one array field per query. We could query `labels` directly, but
> it would force the client to know whether they want EN or PT, and case
> sensitivity. `labelsIndex` makes the query language-agnostic."*

### "What's stopping someone from submitting a 10 GB image?"

> *"Three layers of defense. (1) gRPC has a default per-message limit
> (~4 MB) — we stream in chunks so individual chunks fit. (2) Cloud
> Storage applies per-object limits. (3) Vision API has its own size
> limits (20 MB) and we could pre-validate in the gRPC server. For the
> project scope we trust the client; in production we'd add a server-side
> length check on the streamed bytes."*

### "What happens if Pub/Sub is unavailable when SubmitImage is called?"

> *"Two cases. The Storage write is already committed by the time we
> publish, so the blob exists. If Pub/Sub publish fails we return INTERNAL
> to the client (line 134 of LabelsServiceImpl) — the client knows it can
> safely retry. The duplicate Storage write is a UUID collision (basically
> impossible) so reissuing the same payload generates a fresh request id.
> The orphaned blob from the first attempt would be cleaned by a periodic
> garbage-collection job — we did not implement that for this iteration."*

### "How is the system 'elastic' if scaling takes 90 seconds per VM?"

> *"Elasticity means the system **eventually** matches the load, not that
> scale-up is instant. Cold-start latency is dominated by `apt-get install
> openjdk-17` and the 75 MB JAR download. For production we would bake a
> custom VM image with Java pre-installed and the JAR pre-copied, dropping
> startup to ~10 seconds. We left it as-is to keep the deploy path simple
> for the grading."*

### "Why not use Cloud Run for the workers instead of MIGs?"

> *"The enunciado explicitly requires Compute Engine MIGs for both the
> gRPC server tier and the worker tier. Cloud Run would be a better
> production fit for the worker tier specifically — it auto-scales to
> zero, and our use case (event-driven, stateless) maps cleanly to it.
> Keeping MIGs lets us demonstrate the `ScaleService` RPCs explicitly,
> which Cloud Run hides behind concurrency settings."*

### "What did each team member do?"

Have a one-liner ready per teammate. Even if pair-programming was the
reality, the teacher wants to know everyone contributed something concrete.

---

## 8. Day-of timing cheat sheet (memorise this)

| Min  | What's happening on screen                                          | Who speaks |
| ---- | ------------------------------------------------------------------- | ---------- |
| 0:00 | Slide 1: architecture diagram                                       | A          |
| 0:30 | Slide 2: gRPC contract — SF + SG                                    | A          |
| 1:30 | Slide 3: Pub/Sub message format                                     | A          |
| 2:30 | Slide 4: Firestore schema                                           | A          |
| 4:00 | Switch to GCP Console — show empty bucket / Firestore / queue       | B          |
| 4:30 | Show MIG sizes (1, 0)                                               | B          |
| 5:00 | IntelliJ — Lookup explanation                                       | C          |
| 5:30 | All four students submit one image each                             | A, B, C, D |
| 7:00 | Console: 4 pending Pub/Sub messages, 4 PENDING Firestore docs       | B          |
| 7:45 | Client: Scale Labels-App to 2; talk through resize while VMs boot   | D          |
| 9:30 | Console: Firestore docs flipped to DONE, queue drained              | B          |
| 10:30| Client: Scale gRPC to 2                                             | D          |
| 12:00| Different student picks server #2 in lookup; submits one more image | (any)      |
| 12:30| Queries — GetResult, QueryByLabel('cat'), DownloadImage             | D          |
| 14:00| Recap                                                                | A or D     |
| 14:30| Stop — Q&A starts                                                   | —          |

If you blow past 12 minutes during the demo, skip `DownloadImage` and go
straight to `QueryByLabel` and the recap.

---

## 9. After the presentation

```powershell
.\99-teardown.ps1
```

Don't forget — billing for two idle MIGs accumulates ~€2/day.

---

## 10. Things to *not* do during the demo

- Don't run anything from PowerShell during the screen-share — the PDF
  explicitly says only IntelliJ for the client. All terminal-only tasks
  (clean state, image prep) go in the **pre-flight** section.
- Don't paste UUIDs by hand — the client lets you copy the `requestId`
  with right-click. UUID typos eat 30 seconds you don't have.
- Don't apologise for slow scale-up. Frame it: *"we're now seeing the
  90-second cold-start of a new VM, which is exactly the elasticity
  characteristic the system was designed to expose"*.
- Don't say *"it should work but…"* before pressing Enter. Even if it
  fails, narrate confidently and recover with the §6 backup commands.

Good luck. The system *does* work; the presentation is about showing you
understand **why** each piece is there. Everything in §3 of the PDF is
covered explicitly in this guide; everything in §7 is the most-likely
viva line of attack.
