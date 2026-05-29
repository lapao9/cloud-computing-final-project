package cn2026.worker;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.pubsub.v1.ProjectSubscriptionName;

/**
 * Worker process executed by each VM of the Labels-App MIG.
 *
 * All workers share the same Pub/Sub subscription, so messages are
 * load-balanced across them (work-queue pattern).  Crashing a worker
 * mid-message simply causes Pub/Sub to redeliver after the ack-deadline.
 *
 * Env vars:
 *   CN_PROJECT_ID         - GCP project id
 *   CN_SUBSCRIPTION       - shared Pub/Sub subscription name
 *   CN_FIRESTORE_COL      - Firestore collection (default labels-results)
 *   CN_TARGET_LANG        - translation target (default "pt")
 *   CN_MAX_LABELS         - max labels per image (default 10)
 *   CN_MIN_SCORE          - minimum confidence to keep (default 0.6)
 */
public class LabelsApp {

    public static void main(String[] args) throws Exception {
        String projectId    = req("CN_PROJECT_ID");
        String subscription = req("CN_SUBSCRIPTION");
        String collection   = System.getenv().getOrDefault("CN_FIRESTORE_COL", "labels-results");
        String targetLang   = System.getenv().getOrDefault("CN_TARGET_LANG", "pt");
        int    maxLabels    = Integer.parseInt(System.getenv().getOrDefault("CN_MAX_LABELS", "10"));
        float  minScore     = Float.parseFloat(System.getenv().getOrDefault("CN_MIN_SCORE", "0.6"));

        System.out.println("[Worker] projectId=" + projectId);
        System.out.println("[Worker] subscription=" + subscription);
        System.out.println("[Worker] firestoreCollection=" + collection);
        System.out.println("[Worker] targetLang=" + targetLang
                + " maxLabels=" + maxLabels + " minScore=" + minScore);

        // Reuse a single Vision client, Translate service and Firestore instance
        // for the lifetime of the JVM.
        ImageAnnotatorClient vision = ImageAnnotatorClient.create();
        Translate translate = TranslateOptions.getDefaultInstance().getService();
        Firestore firestore = FirestoreOptions.getDefaultInstance().toBuilder()
                .setProjectId(projectId).build().getService();

        ImageProcessor processor =
                new ImageProcessor(vision, translate, firestore,
                                   collection, targetLang, maxLabels, minScore);

        ProjectSubscriptionName subName =
                ProjectSubscriptionName.of(projectId, subscription);
        Subscriber subscriber = Subscriber.newBuilder(subName, processor).build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Worker] shutting down");
            subscriber.stopAsync();
            vision.close();
        }));

        subscriber.startAsync().awaitRunning();
        System.out.println("[Worker] listening on " + subName);
        subscriber.awaitTerminated();
    }

    private static String req(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Missing env var: " + name);
        return v;
    }
}
