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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.grpc.Context.CancellableContext;

/**
 * Subscribes to the server for result files (e.g., VTU) and writes them into the client's output directory.
 */
public class FileSyncManager {
    private static final Logger logger = LoggerFactory.getLogger(FileSyncManager.class);

    private final SimulationClient simulationClient;
    // Track active subscriptions per simulation ID
    private final Map<String, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();
    
    private static class SubscriptionInfo {
        final AtomicBoolean active = new AtomicBoolean(false);
        CancellableContext context;
    }

    public FileSyncManager(SimulationClient simulationClient) {
        this.simulationClient = simulationClient;
    }

    public void startSync(String simulationId, List<String> filePatterns, boolean includeExisting, String clientOutputDirectory) {
        // Stop any existing sync for this simulation first
        stopSync(simulationId);
        
        // Create new subscription info
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo();
        
        // Check if we're replacing an existing subscription
        SubscriptionInfo existing = activeSubscriptions.putIfAbsent(simulationId, subscriptionInfo);
        if (existing != null) {
            // Stop the existing subscription first
            if (existing.context != null) {
                existing.context.cancel(null);
            }
            // Use the existing object but reset it
            subscriptionInfo = existing;
        }
        
        subscriptionInfo.active.set(true);
        logger.info("Starting sync for simulation: {} to directory: {}", simulationId, clientOutputDirectory);
        System.out.println("FileSyncManager: Starting sync for simulation " + simulationId);

        ensureDirectory(clientOutputDirectory);

        SubscriptionInfo finalSubscriptionInfo = subscriptionInfo;
        CancellableContext context = simulationClient.subscribeResults(simulationId, filePatterns, includeExisting, fileData -> {
            try {
                writeFile(clientOutputDirectory, fileData);
            } catch (IOException e) {
                logger.error("Failed to write synced file", e);
            }
        }, t -> {
            logger.warn("File sync subscription ended for simulation {}: {}", simulationId, t.getMessage());
            System.out.println("FileSyncManager: Sync ended for simulation " + simulationId + ": " + t.getMessage());
            finalSubscriptionInfo.active.set(false);
            // Clean up the entry when subscription ends
            activeSubscriptions.remove(simulationId);
        });
        
        // Store the context for later cancellation
        subscriptionInfo.context = context;
    }
    
    public void stopSync(String simulationId) {
        SubscriptionInfo subscriptionInfo = activeSubscriptions.remove(simulationId);
        if (subscriptionInfo != null) {
            subscriptionInfo.active.set(false);
            if (subscriptionInfo.context != null) {
                subscriptionInfo.context.cancel(null);
                logger.info("Cancelled subscription context for simulation: {}", simulationId);
            }
            logger.info("Stopped sync for simulation: {}", simulationId);
            System.out.println("FileSyncManager: Stopped sync for simulation " + simulationId);
        }
    }
    
    public boolean isSyncing(String simulationId) {
        SubscriptionInfo subscriptionInfo = activeSubscriptions.get(simulationId);
        return subscriptionInfo != null && subscriptionInfo.active.get();
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


