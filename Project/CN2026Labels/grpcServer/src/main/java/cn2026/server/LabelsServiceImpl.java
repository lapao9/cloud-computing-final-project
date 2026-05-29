package cn2026.server;

import cn2026.contract.*;
import com.google.api.core.ApiFuture;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.firestore.*;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Implements the functional service (SF) defined in CN2026Labels.proto.
 *
 *  SubmitImage   - client-streaming: chunks -> Cloud Storage WriteChannel,
 *                  then publishes one Pub/Sub task and replies with the
 *                  generated request id.
 *  GetResult     - reads Firestore document {requestId} from labels-results.
 *  QueryByLabel  - Firestore query on labels[] array_contains + date range.
 *  DownloadImage - streams a blob back to the client in 64KB chunks.
 */
public class LabelsServiceImpl extends LabelsServiceGrpc.LabelsServiceImplBase {

    private final Config cfg;
    private final Storage storage;
    private final Firestore firestore;
    private final Publisher publisher;
    private final Gson gson = new Gson();

    LabelsServiceImpl(Config cfg) throws Exception {
        this.cfg = cfg;
        this.storage = StorageOptions.newBuilder()
                .setProjectId(cfg.projectId).build().getService();
        this.firestore = FirestoreOptions.getDefaultInstance().toBuilder()
                .setProjectId(cfg.projectId).build().getService();
        this.publisher = Publisher.newBuilder(
                TopicName.of(cfg.projectId, cfg.topic)).build();
    }

    // -------------------------------------------------------------- SubmitImage
    @Override
    public StreamObserver<ImageChunk> submitImage(StreamObserver<SubmitResponse> responseObserver) {

        final String requestId = UUID.randomUUID().toString();
        final String[] filenameHolder = new String[1];
        final String blobName = requestId;          // 1:1 mapping requestId <-> blob

        return new StreamObserver<>() {
            WriteChannel writer;
            long bytesWritten = 0;

            @Override
            public void onNext(ImageChunk chunk) {
                try {
                    if (writer == null) {
                        filenameHolder[0] = chunk.getFilename().isBlank()
                                ? blobName : chunk.getFilename();
                        BlobInfo info = BlobInfo.newBuilder(BlobId.of(cfg.bucket, blobName))
                                .setMetadata(Map.of(
                                        "originalFilename", filenameHolder[0],
                                        "requestId",        requestId))
                                .build();
                        writer = storage.writer(info);
                    }
                    byte[] data = chunk.getContent().toByteArray();
                    if (data.length > 0) {
                        writer.write(ByteBuffer.wrap(data));
                        bytesWritten += data.length;
                    }
                } catch (Exception ex) {
                    onError(ex);
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[SubmitImage] upload error: " + t.getMessage());
                closeQuietly(writer);
                responseObserver.onError(Status.INTERNAL
                        .withDescription(t.getMessage()).asRuntimeException());
            }

            @Override
            public void onCompleted() {
                try {
                    closeQuietly(writer);
                    System.out.println("[SubmitImage] " + bytesWritten + " bytes -> gs://"
                            + cfg.bucket + "/" + blobName);

                    // Seed a Firestore doc so that GetResult never returns NOT_FOUND
                    // while the worker is still processing.
                    Map<String, Object> seed = new HashMap<>();
                    seed.put("requestId",   requestId);
                    seed.put("filename",    filenameHolder[0]);
                    seed.put("submittedAt", FieldValue.serverTimestamp());
                    seed.put("status",      "PENDING");
                    seed.put("bucket",      cfg.bucket);
                    seed.put("blob",        blobName);
                    firestore.collection(cfg.firestoreCollection).document(requestId)
                             .set(seed).get();

                    // Publish task to Pub/Sub
                    Map<String, String> task = new HashMap<>();
                    task.put("requestId", requestId);
                    task.put("bucket",    cfg.bucket);
                    task.put("blob",      blobName);
                    task.put("filename",  filenameHolder[0]);

                    PubsubMessage msg = PubsubMessage.newBuilder()
                            .setData(ByteString.copyFromUtf8(gson.toJson(task)))
                            .putAttributes("requestId", requestId)
                            .build();
                    String pubId = publisher.publish(msg).get();
                    System.out.println("[SubmitImage] task published id=" + pubId);

                    responseObserver.onNext(SubmitResponse.newBuilder()
                            .setRequestId(requestId)
                            .setBlobName(blobName)
                            .setBucket(cfg.bucket)
                            .build());
                    responseObserver.onCompleted();
                } catch (Exception ex) {
                    responseObserver.onError(Status.INTERNAL
                            .withDescription(ex.getMessage()).asRuntimeException());
                }
            }
        };
    }

    // ---------------------------------------------------------------- GetResult
    @Override
    public void getResult(RequestId req, StreamObserver<LabelResult> responseObserver) {
        try {
            DocumentSnapshot doc = firestore.collection(cfg.firestoreCollection)
                    .document(req.getRequestId()).get().get();

            if (!doc.exists()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Unknown requestId").asRuntimeException());
                return;
            }

            LabelResult.Builder b = LabelResult.newBuilder()
                    .setRequestId(req.getRequestId())
                    .setFilename(orEmpty(doc.getString("filename")));

            boolean done = "DONE".equals(doc.getString("status"));
            b.setCompleted(done);

            if (done) {
                com.google.cloud.Timestamp ts = doc.getTimestamp("processedAt");
                if (ts != null) b.setProcessedAt(ts.toDate().getTime());

                List<Map<String, Object>> raw =
                        (List<Map<String, Object>>) doc.get("labels");
                if (raw != null) {
                    for (Map<String, Object> m : raw) {
                        b.addLabels(LabelEntry.newBuilder()
                                .setLabelEn(orEmpty((String) m.get("labelEn")))
                                .setLabelPt(orEmpty((String) m.get("labelPt")))
                                .setScore(asFloat(m.get("score")))
                                .build());
                    }
                }
            }
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    // -------------------------------------------------------------- QueryByLabel
    @Override
    public void queryByLabel(LabelDateRange req, StreamObserver<FileNames> responseObserver) {
        try {
            String wanted = req.getLabel().toLowerCase(Locale.ROOT);
            // We store an index field "labelsIndex" (lower-cased en+pt) to support
            // a single array-contains, then filter timestamps below.
            Query q = firestore.collection(cfg.firestoreCollection)
                    .whereArrayContains("labelsIndex", wanted);

            ApiFuture<QuerySnapshot> fut = q.get();
            FileNames.Builder reply = FileNames.newBuilder();
            for (DocumentSnapshot d : fut.get().getDocuments()) {
                com.google.cloud.Timestamp ts = d.getTimestamp("processedAt");
                if (ts == null) continue;            // skip still-pending docs
                long t = ts.toDate().getTime();
                if (req.getStartEpochMillis() > 0 && t < req.getStartEpochMillis()) continue;
                if (req.getEndEpochMillis()   > 0 && t > req.getEndEpochMillis())   continue;
                String fn = d.getString("filename");
                if (fn != null) reply.addFilename(fn);
            }
            responseObserver.onNext(reply.build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    // ------------------------------------------------------------- DownloadImage
    @Override
    public void downloadImage(RequestId req, StreamObserver<ImageChunk> responseObserver) {
        try {
            DocumentSnapshot doc = firestore.collection(cfg.firestoreCollection)
                    .document(req.getRequestId()).get().get();
            if (!doc.exists()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Unknown requestId").asRuntimeException());
                return;
            }
            String bucket  = doc.getString("bucket");
            String blobN   = doc.getString("blob");
            String filename = orEmpty(doc.getString("filename"));

            Blob blob = storage.get(BlobId.of(bucket, blobN));
            if (blob == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Blob missing in Cloud Storage").asRuntimeException());
                return;
            }

            try (ReadChannel reader = blob.reader()) {
                ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
                boolean first = true;
                while (reader.read(buf) > 0) {
                    buf.flip();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    ImageChunk.Builder cb = ImageChunk.newBuilder()
                            .setContent(ByteString.copyFrom(data));
                    if (first) { cb.setFilename(filename); first = false; }
                    responseObserver.onNext(cb.build());
                    buf.clear();
                }
            }
            responseObserver.onCompleted();
        } catch (Exception ex) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    // ---------------------------------------------------------------- helpers
    private static void closeQuietly(WriteChannel ch) {
        if (ch != null) try { ch.close(); } catch (Exception ignored) {}
    }
    private static String orEmpty(String s) { return s == null ? "" : s; }
    private static float asFloat(Object o) {
        if (o == null) return 0f;
        if (o instanceof Number n) return n.floatValue();
        return Float.parseFloat(o.toString());
    }
}
