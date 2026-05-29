package cn2026.worker;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.Translation;
import com.google.cloud.vision.v1.*;
import com.google.gson.Gson;
import com.google.pubsub.v1.PubsubMessage;

import java.util.*;

/**
 * One instance per JVM (shared across worker threads inside the
 * Subscriber's executor).  All Google clients are thread-safe so we
 * never create them per-message.
 *
 * Acknowledgement policy: ack() only AFTER the Firestore write succeeds.
 * If anything fails we nack(), Pub/Sub will redeliver (work-queue),
 * and the duplicate write is harmless because the document id is the
 * (stable) requestId.
 */
public class ImageProcessor implements MessageReceiver {

    private final ImageAnnotatorClient vision;
    private final Translate translate;
    private final Firestore firestore;
    private final String collection;
    private final String targetLang;
    private final int    maxLabels;
    private final float  minScore;
    private final Gson   gson = new Gson();

    public ImageProcessor(ImageAnnotatorClient vision, Translate translate,
                          Firestore firestore, String collection,
                          String targetLang, int maxLabels, float minScore) {
        this.vision = vision;
        this.translate = translate;
        this.firestore = firestore;
        this.collection = collection;
        this.targetLang = targetLang;
        this.maxLabels = maxLabels;
        this.minScore = minScore;
    }

    @Override
    public void receiveMessage(PubsubMessage msg, AckReplyConsumer ack) {
        String body = msg.getData().toStringUtf8();
        Task task;
        try {
            task = gson.fromJson(body, Task.class);
            Objects.requireNonNull(task.requestId);
            Objects.requireNonNull(task.bucket);
            Objects.requireNonNull(task.blob);
        } catch (Exception ex) {
            System.err.println("[Worker] malformed message, dropping: " + body);
            ack.ack(); // poison message -> dont retry forever
            return;
        }

        System.out.println("[Worker] processing requestId=" + task.requestId
                + " blob=gs://" + task.bucket + "/" + task.blob);

        try {
            List<EntityAnnotation> rawLabels =
                    detectLabels("gs://" + task.bucket + "/" + task.blob);

            List<Map<String, Object>> labels = new ArrayList<>();
            List<String> index = new ArrayList<>();   // for whereArrayContains lookups

            int kept = 0;
            for (EntityAnnotation a : rawLabels) {
                if (kept >= maxLabels) break;
                if (a.getScore() < minScore) continue;
                String en = a.getDescription();
                String pt = translateLabel(en);
                Map<String, Object> entry = new HashMap<>();
                entry.put("labelEn", en);
                entry.put("labelPt", pt);
                entry.put("score",   a.getScore());
                labels.add(entry);
                index.add(en.toLowerCase(Locale.ROOT));
                if (pt != null) index.add(pt.toLowerCase(Locale.ROOT));
                kept++;
            }

            Map<String, Object> update = new HashMap<>();
            update.put("status",       "DONE");
            update.put("processedAt",  FieldValue.serverTimestamp());
            update.put("labels",       labels);
            update.put("labelsIndex",  index);
            update.put("filename",     task.filename);   // keep for query results
            update.put("bucket",       task.bucket);
            update.put("blob",         task.blob);

            firestore.collection(collection).document(task.requestId)
                     .set(update, com.google.cloud.firestore.SetOptions.merge())
                     .get();

            System.out.println("[Worker] done requestId=" + task.requestId
                    + " labels=" + labels.size());
            ack.ack();
        } catch (Exception ex) {
            System.err.println("[Worker] failed requestId=" + task.requestId
                    + ": " + ex.getMessage());
            ex.printStackTrace();
            ack.nack();   // Pub/Sub will redeliver
        }
    }

    // ----------------------------------------------------------------- helpers

    private List<EntityAnnotation> detectLabels(String gsUri) throws Exception {
        Image img = Image.newBuilder()
                .setSource(ImageSource.newBuilder().setImageUri(gsUri).build())
                .build();
        Feature feat = Feature.newBuilder().setType(Feature.Type.LABEL_DETECTION).build();
        AnnotateImageRequest req = AnnotateImageRequest.newBuilder()
                .addFeatures(feat).setImage(img).build();
        BatchAnnotateImagesResponse resp =
                vision.batchAnnotateImages(List.of(req));
        AnnotateImageResponse first = resp.getResponses(0);
        if (first.hasError()) {
            throw new RuntimeException("Vision error: " + first.getError().getMessage());
        }
        return first.getLabelAnnotationsList();
    }

    private String translateLabel(String text) {
        try {
            Translation t = translate.translate(text,
                    Translate.TranslateOption.sourceLanguage("en"),
                    Translate.TranslateOption.targetLanguage(targetLang));
            return t.getTranslatedText();
        } catch (Exception ex) {
            System.err.println("[Worker] translate failed for '" + text + "': " + ex.getMessage());
            return text; // fall back to English
        }
    }

    // Gson DTO for the Pub/Sub payload.
    private static class Task {
        String requestId;
        String bucket;
        String blob;
        String filename;
    }
}
