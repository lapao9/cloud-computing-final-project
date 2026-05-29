package cn2026.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class GrpcServerMain {

    public static void main(String[] args) throws Exception {

        Config cfg = Config.fromEnv();
        cfg.dump();

        Server svc = ServerBuilder.forPort(cfg.port)
                .addService(new LabelsServiceImpl(cfg))
                .addService(new ScaleServiceImpl(cfg))
                .build()
                .start();

        System.out.println("[gRPC] server started on port " + cfg.port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[gRPC] shutting down");
            svc.shutdown();
        }));
        svc.awaitTermination();
    }
}
