package com.simlab.ug.client;

import com.simlab.ug.grpc.FileData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to the server for result files (e.g., VTU) and writes them into the client's output directory.
 */
public class FileSyncManager {
    private static final Logger logger = LoggerFactory.getLogger(FileSyncManager.class);

    private final SimulationClient simulationClient;
    private final AtomicBoolean subscriptionActive = new AtomicBoolean(false);

    public FileSyncManager(SimulationClient simulationClient) {
        this.simulationClient = simulationClient;
    }

    public void startSync(String simulationId, List<String> filePatterns, boolean includeExisting, String clientOutputDirectory) {
        if (subscriptionActive.get()) return;
        subscriptionActive.set(true);

        ensureDirectory(clientOutputDirectory);

        simulationClient.subscribeResults(simulationId, filePatterns, includeExisting, fileData -> {
            try {
                writeFile(clientOutputDirectory, fileData);
            } catch (IOException e) {
                logger.error("Failed to write synced file", e);
            }
        }, t -> {
            logger.warn("File sync subscription ended with error: {}", t.getMessage());
            subscriptionActive.set(false);
        });
    }

    private void ensureDirectory(String dir) {
        try {
            Files.createDirectories(Paths.get(dir));
        } catch (IOException e) {
            logger.error("Failed to create output directory {}", dir, e);
        }
    }

    private void writeFile(String baseDir, FileData fileData) throws IOException {
        Path outPath = Paths.get(baseDir, fileData.getFilename());
        File outFile = outPath.toFile();
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(fileData.getContent().toByteArray());
        }
        logger.info("Synced file: {} ({} bytes)", outPath, fileData.getContent().size());
    }
}


