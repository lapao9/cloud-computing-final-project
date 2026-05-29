package cn2026.server;

import cn2026.contract.*;
import com.google.cloud.compute.v1.InstanceGroupManagersClient;
import com.google.cloud.compute.v1.InstanceGroupManager;
import com.google.cloud.compute.v1.ManagedInstance;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;

/**
 * Implements the management service (SG).
 *
 * Uses InstanceGroupManagersClient.resize() to grow/shrink the two MIGs.
 * The VMs hosting this gRPC server need a service account with the
 *   roles/compute.instanceAdmin.v1 (or at least compute.instanceGroupManager.use)
 * granted; the JAR uses Application Default Credentials.
 */
public class ScaleServiceImpl extends ScaleServiceGrpc.ScaleServiceImplBase {

    private final Config cfg;

    ScaleServiceImpl(Config cfg) {
        this.cfg = cfg;
    }

    @Override
    public void scaleGrpcServers(ScaleRequest req, StreamObserver<ScaleResponse> resp) {
        resize(cfg.migGrpc, req.getTargetSize(), resp);
    }

    @Override
    public void scaleLabelsApp(ScaleRequest req, StreamObserver<ScaleResponse> resp) {
        resize(cfg.migLabels, req.getTargetSize(), resp);
    }

    @Override
    public void getStatus(Empty req, StreamObserver<StatusResponse> resp) {
        try (InstanceGroupManagersClient client = InstanceGroupManagersClient.create()) {
            resp.onNext(StatusResponse.newBuilder()
                    .setGrpcServers(snapshot(client, cfg.migGrpc))
                    .setLabelsApp  (snapshot(client, cfg.migLabels))
                    .build());
            resp.onCompleted();
        } catch (Exception ex) {
            resp.onError(Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    // ----------------------------------------------------------------- helpers

    private void resize(String migName, int target, StreamObserver<ScaleResponse> resp) {
        if (target < 0) {
            resp.onError(Status.INVALID_ARGUMENT
                    .withDescription("target_size must be >= 0").asRuntimeException());
            return;
        }
        try (InstanceGroupManagersClient client = InstanceGroupManagersClient.create()) {
            client.resizeAsync(cfg.projectId, cfg.zone, migName, target).get();
            InstanceGroupManager mig = client.get(cfg.projectId, cfg.zone, migName);
            System.out.println("[Scale] " + migName + " -> " + target);
            resp.onNext(ScaleResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Resized " + migName + " to " + target)
                    .setCurrentSize(mig.getTargetSize())
                    .build());
            resp.onCompleted();
        } catch (Exception ex) {
            resp.onError(Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
        }
    }

    private MigInfo snapshot(InstanceGroupManagersClient client, String migName) {
        InstanceGroupManager mig = client.get(cfg.projectId, cfg.zone, migName);
        MigInfo.Builder b = MigInfo.newBuilder()
                .setMigName(migName)
                .setTargetSize(mig.getTargetSize());
        try {
            for (ManagedInstance mi : client.listManagedInstances(
                    cfg.projectId, cfg.zone, migName).iterateAll()) {
                String n = mi.getInstance();           // full URL .../instances/<name>
                b.addInstanceNames(n.substring(n.lastIndexOf('/') + 1));
            }
        } catch (Exception ignored) {}
        return b.build();
    }
}
