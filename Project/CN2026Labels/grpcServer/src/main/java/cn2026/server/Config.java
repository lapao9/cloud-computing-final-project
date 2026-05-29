package cn2026.server;

/**
 * Runtime configuration of the gRPC server.
 * Every value is supplied via environment variables so that the same JAR
 * can be reused inside the MIG startup script without rebuilding.
 *
 * Required (no sensible default):
 *   CN_PROJECT_ID       GCP project id
 *   CN_BUCKET           Cloud Storage bucket used for raw images
 *   CN_TOPIC            Pub/Sub topic for worker tasks
 *   CN_REGION           Region for Compute Engine MIGs (e.g. europe-west1)
 *   CN_ZONE             Zone for Compute Engine MIGs (e.g. europe-west1-b)
 *   CN_MIG_GRPC         Name of the gRPC server MIG
 *   CN_MIG_LABELS       Name of the Labels-App MIG
 *
 * Optional:
 *   CN_PORT             gRPC listen port (default 8000)
 *   CN_FIRESTORE_COL    Firestore collection name (default labels-results)
 */
public class Config {

    public final String projectId;
    public final String bucket;
    public final String topic;
    public final String zone;
    public final String region;
    public final String migGrpc;
    public final String migLabels;
    public final int    port;
    public final String firestoreCollection;

    private Config(String projectId, String bucket, String topic,
                   String zone, String region, String migGrpc, String migLabels,
                   int port, String firestoreCollection) {
        this.projectId = projectId;
        this.bucket = bucket;
        this.topic = topic;
        this.zone = zone;
        this.region = region;
        this.migGrpc = migGrpc;
        this.migLabels = migLabels;
        this.port = port;
        this.firestoreCollection = firestoreCollection;
    }

    static Config fromEnv() {
        return new Config(
            require("CN_PROJECT_ID"),
            require("CN_BUCKET"),
            require("CN_TOPIC"),
            require("CN_ZONE"),
            require("CN_REGION"),
            require("CN_MIG_GRPC"),
            require("CN_MIG_LABELS"),
            Integer.parseInt(System.getenv().getOrDefault("CN_PORT", "8000")),
            System.getenv().getOrDefault("CN_FIRESTORE_COL", "labels-results")
        );
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return v;
    }

    void dump() {
        System.out.println("[Config] projectId=" + projectId);
        System.out.println("[Config] bucket=" + bucket);
        System.out.println("[Config] topic=" + topic);
        System.out.println("[Config] region/zone=" + region + "/" + zone);
        System.out.println("[Config] migGrpc=" + migGrpc);
        System.out.println("[Config] migLabels=" + migLabels);
        System.out.println("[Config] port=" + port);
        System.out.println("[Config] firestoreCollection=" + firestoreCollection);
    }
}
