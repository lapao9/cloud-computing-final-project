package cn2026.client;

import cn2026.contract.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Interactive console client for CN2026Labels.
 *
 * Usage:
 *   java -jar cn2026-labels-client.jar <LOOKUP_URL>
 *
 * The client never hardcodes server IPs. It always retrieves them from
 * the lookup function and lets the user pick one, retrying on connection
 * failure.
 */
public class ClientApp {

    private static final Scanner SC = new Scanner(System.in);

    private static LookupClient lookup;
    private static ManagedChannel channel;
    private static LabelsServiceGrpc.LabelsServiceStub          asyncStub;
    private static LabelsServiceGrpc.LabelsServiceBlockingStub  blockingStub;
    private static ScaleServiceGrpc.ScaleServiceBlockingStub    scaleStub;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar cn2026-labels-client.jar <lookup-url>");
            System.exit(1);
        }
        lookup = new LookupClient(args[0]);

        if (!connect()) {
            System.err.println("Could not connect to any gRPC server. Bye.");
            return;
        }

        while (true) {
            int op = menu();
            try {
                switch (op) {
                    case 1 -> submitImage();
                    case 2 -> getResult();
                    case 3 -> queryByLabel();
                    case 4 -> downloadImage();
                    case 5 -> scaleGrpc();
                    case 6 -> scaleWorkers();
                    case 7 -> showStatus();
                    case 8 -> connect();              // re-lookup
                    case 9 -> { shutdown(); return; }
                    default -> System.out.println("?");
                }
            } catch (Exception ex) {
                System.err.println("Call failed: " + ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------- connection / lookup

    private static boolean connect() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<LookupClient.Server> servers = lookup.list();
                if (servers.isEmpty()) {
                    System.out.println("[Lookup] no gRPC servers available, retrying...");
                    Thread.sleep(2000);
                    continue;
                }
                System.out.println("Available gRPC servers:");
                for (int i = 0; i < servers.size(); i++) {
                    LookupClient.Server s = servers.get(i);
                    System.out.println("  " + (i + 1) + ") " + s.name() + "  " + s.ip() + ":" + s.port());
                }
                int idx = readInt("Pick one (0 = auto-first):");
                LookupClient.Server pick = servers.get(idx <= 0 ? 0 : Math.min(idx - 1, servers.size() - 1));
                return openChannel(pick.ip(), pick.port());
            } catch (Exception ex) {
                System.err.println("[Lookup] " + ex.getMessage());
            }
        }
        return false;
    }

    private static boolean openChannel(String ip, int port) {
        if (channel != null) channel.shutdownNow();
        System.out.println("[gRPC] connecting to " + ip + ":" + port);
        channel = ManagedChannelBuilder.forAddress(ip, port)
                .usePlaintext().build();
        asyncStub    = LabelsServiceGrpc.newStub(channel);
        blockingStub = LabelsServiceGrpc.newBlockingStub(channel);
        scaleStub    = ScaleServiceGrpc.newBlockingStub(channel);
        return true;
    }

    private static void shutdown() {
        if (channel != null) channel.shutdownNow();
    }

    // ---------------------------------------------------------- operations

    private static void submitImage() throws Exception {
        String path = readLine("Local image path:");
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            System.out.println("File not found: " + path);
            return;
        }
        String filename = p.getFileName().toString();

        CountDownLatch done = new CountDownLatch(1);
        final SubmitResponse[] reply = new SubmitResponse[1];

        StreamObserver<ImageChunk> outbound = asyncStub.submitImage(new StreamObserver<>() {
            @Override public void onNext(SubmitResponse r) { reply[0] = r; }
            @Override public void onError(Throwable t) {
                System.err.println("SubmitImage onError: " + t.getMessage());
                done.countDown();
            }
            @Override public void onCompleted() { done.countDown(); }
        });

        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[64 * 1024];
            boolean first = true;
            int n;
            while ((n = in.read(buf)) > 0) {
                ImageChunk.Builder cb = ImageChunk.newBuilder()
                        .setContent(ByteString.copyFrom(buf, 0, n));
                if (first) { cb.setFilename(filename); first = false; }
                outbound.onNext(cb.build());
            }
        }
        outbound.onCompleted();

        if (!done.await(60, TimeUnit.SECONDS)) {
            System.err.println("Timed out waiting for server reply");
            return;
        }
        if (reply[0] == null) return;
        System.out.println("OK: requestId=" + reply[0].getRequestId()
                + "  blob=gs://" + reply[0].getBucket() + "/" + reply[0].getBlobName());
    }

    private static void getResult() {
        String id = readLine("requestId:");
        LabelResult r = blockingStub.getResult(RequestId.newBuilder().setRequestId(id).build());
        System.out.println("filename     = " + r.getFilename());
        System.out.println("completed    = " + r.getCompleted());
        System.out.println("processedAt  = " + (r.getProcessedAt() == 0
                ? "(pending)"
                : new Date(r.getProcessedAt())));
        for (LabelEntry e : r.getLabelsList()) {
            System.out.printf("  - %-20s %-20s (%.2f)%n",
                    e.getLabelEn(), e.getLabelPt(), e.getScore());
        }
    }

    private static void queryByLabel() throws Exception {
        String label = readLine("label (en or pt):");
        long start = readDateAsMillis("start date (yyyy-MM-dd, blank = no lower bound):");
        long end   = readDateAsMillis("end date   (yyyy-MM-dd, blank = no upper bound):");

        FileNames res = blockingStub.queryByLabel(LabelDateRange.newBuilder()
                .setLabel(label)
                .setStartEpochMillis(start)
                .setEndEpochMillis(end)
                .build());
        System.out.println("Files (" + res.getFilenameCount() + "):");
        for (String f : res.getFilenameList()) System.out.println("  " + f);
    }

    private static void downloadImage() throws Exception {
        String id = readLine("requestId:");
        String dest = readLine("Local destination path:");
        Iterator<ImageChunk> it = blockingStub.downloadImage(
                RequestId.newBuilder().setRequestId(id).build());
        try (FileOutputStream out = new FileOutputStream(dest)) {
            while (it.hasNext()) out.write(it.next().getContent().toByteArray());
        }
        System.out.println("Downloaded to " + dest);
    }

    private static void scaleGrpc() {
        int n = readInt("Target size for gRPC server MIG:");
        ScaleResponse r = scaleStub.scaleGrpcServers(
                ScaleRequest.newBuilder().setTargetSize(n).build());
        System.out.println(r.getMessage() + "  current=" + r.getCurrentSize());
    }

    private static void scaleWorkers() {
        int n = readInt("Target size for Labels-App MIG:");
        ScaleResponse r = scaleStub.scaleLabelsApp(
                ScaleRequest.newBuilder().setTargetSize(n).build());
        System.out.println(r.getMessage() + "  current=" + r.getCurrentSize());
    }

    private static void showStatus() {
        StatusResponse r = scaleStub.getStatus(Empty.getDefaultInstance());
        printMig("gRPC servers", r.getGrpcServers());
        printMig("Labels-App  ", r.getLabelsApp());
    }

    private static void printMig(String label, MigInfo m) {
        System.out.println(label + ": " + m.getMigName() + " target=" + m.getTargetSize());
        for (String n : m.getInstanceNamesList()) System.out.println("    - " + n);
    }

    // ---------------------------------------------------------- menu + utilities

    private static int menu() {
        System.out.println();
        System.out.println("=== CN2026Labels client ===");
        System.out.println("1 - Submit image");
        System.out.println("2 - Get result by requestId");
        System.out.println("3 - Query files by label and date range");
        System.out.println("4 - Download image by requestId");
        System.out.println("5 - Scale gRPC server MIG");
        System.out.println("6 - Scale Labels-App MIG");
        System.out.println("7 - Show MIG status");
        System.out.println("8 - Re-lookup and reconnect");
        System.out.println("9 - Exit");
        return readInt("?");
    }

    private static int readInt(String msg) {
        while (true) {
            System.out.print(msg + " ");
            String s = SC.nextLine().trim();
            try { return Integer.parseInt(s); } catch (NumberFormatException e) {
                System.out.println("Not a number.");
            }
        }
    }
    private static String readLine(String msg) {
        System.out.print(msg + " ");
        return SC.nextLine().trim();
    }
    private static long readDateAsMillis(String msg) {
        String s = readLine(msg);
        if (s.isBlank()) return 0;
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(s).getTime();
        } catch (Exception ex) {
            System.out.println("Bad date, treating as 'no bound'.");
            return 0;
        }
    }
}
