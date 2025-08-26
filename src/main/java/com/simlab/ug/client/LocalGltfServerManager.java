package com.simlab.ug.client;

import com.simlab.ug.server.ResultsServiceImpl;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Runs a local gRPC ResultsService server inside the client to serve GLTF files from the client's machine.
 */
public class LocalGltfServerManager {
    private static final Logger logger = LoggerFactory.getLogger(LocalGltfServerManager.class);

    private final int port;
    private final String rootDirectory;
    private final ResultsServiceImpl resultsService;
    private Server server;

    public LocalGltfServerManager(int port, String rootDirectory) {
        this.port = port;
        this.rootDirectory = rootDirectory;
        this.resultsService = new ResultsServiceImpl(rootDirectory);
    }

    public void start() throws IOException {
        if (server != null) stop();
        server = ServerBuilder.forPort(port)
                .addService(resultsService)
                .build()
                .start();
        logger.info("Local GLTF Results server started on port {} (root: {})", port, rootDirectory);
    }

    public void stop() {
        if (server != null) {
            try {
                server.shutdownNow();
            } finally {
                server = null;
            }
            logger.info("Local GLTF Results server stopped");
        }
    }

    public void setGroupPatterns(List<String> patterns) {
        // Future extension: inject patterns into ResultsServiceImpl if supported
    }
}


