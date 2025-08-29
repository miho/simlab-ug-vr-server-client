package com.simlab.ug.server;

import com.simlab.ug.grpc.*;
import com.simlab.ug.common.LuaScriptParser;
import com.simlab.ug.common.SimulationExecutor;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

public class SimulationServiceImpl extends SimulationServiceGrpc.SimulationServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(SimulationServiceImpl.class);
    
    private final Map<String, SimulationExecutor> activeSimulations = new ConcurrentHashMap<>();
    private final Map<String, String> completedSimulationDirs = new ConcurrentHashMap<>();
    private final Map<String, ResultWatcher> activeWatchers = new ConcurrentHashMap<>();
    private final AtomicInteger watcherCounter = new AtomicInteger(0);
    private String ugPath = "";
    private String workingDirectory = System.getProperty("user.dir");
    private final LuaScriptParser scriptParser = new LuaScriptParser();
    private ResultsServiceImpl resultsService;
    
    @Override
    public void getServerStatus(Empty request, StreamObserver<ServerStatus> responseObserver) {
        ServerStatus.Builder status = ServerStatus.newBuilder()
                .setIsRunning(true)
                .setUgPath(ugPath)
                .setWorkingDirectory(workingDirectory);
        
        activeSimulations.forEach((id, executor) -> {
            status.addActiveSimulations(ActiveSimulation.newBuilder()
                    .setSimulationId(id)
                    .setScriptPath(executor.getScriptPath())
                    .setState(executor.getState())
                    .setProgress(executor.getProgress())
                    .build());
        });
        
        responseObserver.onNext(status.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void setWorkingDirectory(SetWorkingDirectoryRequest request, 
                                   StreamObserver<StatusResponse> responseObserver) {
        File dir = new File(request.getDirectory());
        if (dir.exists() && dir.isDirectory()) {
            workingDirectory = request.getDirectory();
            // Keep ResultsService root directory in sync
            if (resultsService != null) {
                try {
                    resultsService.setDefaultRootDirectory(workingDirectory);
                } catch (Exception ignored) {}
            }
            responseObserver.onNext(StatusResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Working directory set to: " + workingDirectory)
                    .build());
        } else {
            responseObserver.onNext(StatusResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid directory: " + request.getDirectory())
                    .build());
        }
        responseObserver.onCompleted();
    }
    
    @Override
    public void analyzeScript(AnalyzeScriptRequest request, 
                              StreamObserver<ScriptAnalysis> responseObserver) {
        try {
            File scriptFile = new File(request.getScriptPath());
            if (!scriptFile.exists()) {
                responseObserver.onNext(ScriptAnalysis.newBuilder()
                        .setSuccess(false)
                        .setErrorMessage("Script file not found: " + request.getScriptPath())
                        .build());
                responseObserver.onCompleted();
                return;
            }
            
            ScriptAnalysis analysis = scriptParser.analyzeScript(scriptFile);
            responseObserver.onNext(analysis);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error analyzing script", e);
            responseObserver.onNext(ScriptAnalysis.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void runSimulation(RunSimulationRequest request, 
                             StreamObserver<SimulationUpdate> responseObserver) {
        String simulationId = request.getSimulationId();
        if (simulationId == null || simulationId.isEmpty()) {
            simulationId = UUID.randomUUID().toString();
        }
        
        try {
            SimulationExecutor executor = new SimulationExecutor(
                    simulationId,
                    request.getScriptPath(),
                    ugPath,
                    request.getParametersList(),
                    workingDirectory,
                    Files.createTempDirectory("ug-simulation-"+simulationId).toString()
            );
            
            activeSimulations.put(simulationId, executor);

            String finalSimulationId = simulationId;
            executor.execute(new SimulationExecutor.UpdateListener() {
                @Override
                public void onProgress(double percentage, String message, int current, int total) {
                    responseObserver.onNext(SimulationUpdate.newBuilder()
                            .setSimulationId(finalSimulationId)
                            .setType(UpdateType.PROGRESS)
                            .setProgress(ProgressUpdate.newBuilder()
                                    .setPercentage(percentage)
                                    .setMessage(message)
                                    .setCurrentStep(current)
                                    .setTotalSteps(total)
                                    .build())
                            .build());
                }
                
                @Override
                public void onLog(LogLevel level, String message) {
                    responseObserver.onNext(SimulationUpdate.newBuilder()
                            .setSimulationId(finalSimulationId)
                            .setType(UpdateType.LOG)
                            .setLog(LogMessage.newBuilder()
                                    .setLevel(level)
                                    .setMessage(message)
                                    .setTimestamp(System.currentTimeMillis())
                                    .build())
                            .build());
                }
                
                @Override
                public void onComplete(SimulationState state, long duration, java.util.List<String> outputFiles) {
                    // Store the output directory for completed simulations
                    SimulationExecutor completedExecutor = activeSimulations.get(finalSimulationId);
                    if (completedExecutor != null && completedExecutor.getOutputDirectory() != null) {
                        completedSimulationDirs.put(finalSimulationId, completedExecutor.getOutputDirectory());
                        logger.info("Stored output directory for completed simulation " + finalSimulationId + 
                                   ": " + completedExecutor.getOutputDirectory());
                    }
                    
                    // Stop watchers for completed simulation
                    stopWatchersForSimulation(finalSimulationId);
                    
                    responseObserver.onNext(SimulationUpdate.newBuilder()
                            .setSimulationId(finalSimulationId)
                            .setType(UpdateType.RESULT)
                            .setResult(SimulationResult.newBuilder()
                                    .setFinalState(state)
                                    .setDurationMs(duration)
                                    .addAllOutputFiles(outputFiles)
                                    .setSummary("Simulation completed")
                                    .build())
                            .build());
                    responseObserver.onCompleted();
                    activeSimulations.remove(finalSimulationId);
                }
                
                @Override
                public void onError(String error, String stackTrace) {
                    // Stop watchers for failed simulation
                    stopWatchersForSimulation(finalSimulationId);
                    
                    responseObserver.onNext(SimulationUpdate.newBuilder()
                            .setSimulationId(finalSimulationId)
                            .setType(UpdateType.UPDATE_ERROR)
                            .setError(ErrorMessage.newBuilder()
                                    .setError(error)
                                    .setStackTrace(stackTrace)
                                    .build())
                            .build());
                    activeSimulations.remove(finalSimulationId);
                }
            });
            
        } catch (Exception e) {
            logger.error("Error starting simulation", e);
            responseObserver.onNext(SimulationUpdate.newBuilder()
                    .setSimulationId(simulationId)
                    .setType(UpdateType.UPDATE_ERROR)
                    .setError(ErrorMessage.newBuilder()
                            .setError(e.getMessage())
                            .setStackTrace(getStackTrace(e))
                            .build())
                    .build());
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void stopSimulation(StopSimulationRequest request, 
                              StreamObserver<StatusResponse> responseObserver) {
        String simulationId = request.getSimulationId();
        SimulationExecutor executor = activeSimulations.get(simulationId);
        
        if (executor != null) {
            executor.stop();
            activeSimulations.remove(simulationId);
            
            // Stop and remove any watchers for this simulation
            stopWatchersForSimulation(simulationId);
            
            responseObserver.onNext(StatusResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Simulation stopped: " + simulationId)
                    .build());
        } else {
            responseObserver.onNext(StatusResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Simulation not found: " + simulationId)
                    .build());
        }
        responseObserver.onCompleted();
    }
    
    @Override
    public void getSimulationResults(GetResultsRequest request, 
                                    StreamObserver<FileData> responseObserver) {
        try {
            String simulationId = request.getSimulationId();
            logger.info("Getting results for simulation: " + simulationId);
            
            // First check if there's an active simulation with custom output directory
            SimulationExecutor executor = activeSimulations.get(simulationId);
            Path outputDir = null;
            
            if (executor != null && executor.getOutputDirectory() != null) {
                // Use the executor's output directory
                File outDir = new File(executor.getOutputDirectory());
                if (!outDir.isAbsolute()) {
                    outDir = new File(workingDirectory, executor.getOutputDirectory());
                }
                outputDir = outDir.toPath();
                logger.info("Using active executor output directory: " + outputDir);
            } else if (completedSimulationDirs.containsKey(simulationId)) {
                // Check completed simulations
                String completedDir = completedSimulationDirs.get(simulationId);
                File outDir = new File(completedDir);
                if (!outDir.isAbsolute()) {
                    outDir = new File(workingDirectory, completedDir);
                }
                outputDir = outDir.toPath();
                logger.info("Using completed simulation output directory: " + outputDir);
            } else {
                // Fall back to default output directory structure
                outputDir = Paths.get(workingDirectory, "output", simulationId);
                logger.info("Using default output directory: " + outputDir);
            }
            
            if (!Files.exists(outputDir)) {
                logger.warn("Output directory does not exist: " + outputDir);
                responseObserver.onCompleted();
                return;
            }
            
            List<String> patterns = request.getFilePatternsList();
            logger.info("Looking for files matching patterns: " + patterns);
            
            Files.walk(outputDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesPatterns(path, patterns))
                    .forEach(path -> {
                        try {

                            try {
                                boolean ready = FileWriteDetector.waitUntilReady(path);

                                if(!ready) {
                                    logger.info("File not ready: " + path);
                                    System.out.println("File not ready: " + path);
                                    return;
                                } else {
                                    logger.info("File ready: " + path);
                                    System.out.println("File ready: " + path);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }

                            logger.info("Sending file: " + path.getFileName());
                            System.out.println("Sending file: " + path.getFileName());

                            byte[] content = Files.readAllBytes(path);
                            String mimeType = Files.probeContentType(path);
                            if (mimeType == null) {
                                mimeType = "application/octet-stream";
                            }
                            
                            responseObserver.onNext(FileData.newBuilder()
                                    .setFilename(path.getFileName().toString())
                                    .setContent(com.google.protobuf.ByteString.copyFrom(content))
                                    .setMimeType(mimeType)
                                    .build());
                        } catch (IOException e) {
                            logger.error("Error reading file: " + path, e);
                            System.out.println("Error reading file: " + path);
                        }
                    });
            
            responseObserver.onCompleted();
            logger.info("Completed sending results for simulation: " + simulationId);
        } catch (Exception e) {
            logger.error("Error getting simulation results", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void subscribeResults(SubscribeResultsRequest request, StreamObserver<FileData> responseObserver) {

        logger.info("Subscribing to results for simulation: " + request.getSimulationId());

        try {
            String simulationId = request.getSimulationId();
            java.util.List<String> patterns = request.getFilePatternsList();
            boolean includeExisting = request.getIncludeExisting();
            
            // Generate unique watcher ID for this subscription
            String watcherId = simulationId + "_watcher_" + watcherCounter.incrementAndGet();

            // Resolve the output directory similar to getSimulationResults
            Path outputDir;
            SimulationExecutor executor = activeSimulations.get(simulationId);
            if (executor != null && executor.getOutputDirectory() != null) {
                File outDir = new File(executor.getOutputDirectory());
                if (!outDir.isAbsolute()) {
                    outDir = new File(workingDirectory, executor.getOutputDirectory());
                }
                outputDir = outDir.toPath();
            } else if (completedSimulationDirs.containsKey(simulationId)) {
                String completedDir = completedSimulationDirs.get(simulationId);
                File outDir = new File(completedDir);
                if (!outDir.isAbsolute()) {
                    outDir = new File(workingDirectory, completedDir);
                }
                outputDir = outDir.toPath();
            } else {
                outputDir = Paths.get(workingDirectory);
            }

            if (!Files.exists(outputDir)) {
                responseObserver.onCompleted();
                return;
            }

            // Optionally send existing files first
            if (includeExisting) {
                try {
                    Files.walk(outputDir)
                            .filter(Files::isRegularFile)
                            .filter(path -> matchesPatterns(path, patterns))
                            .forEach(path -> {
                                try {

                                    try {
                                        FileWriteDetector.waitUntilReady(path);
                                    } catch (IOException | InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }

                                    byte[] content = Files.readAllBytes(path);
                                    String mimeType = Files.probeContentType(path);
                                    if (mimeType == null) mimeType = "application/octet-stream";

                                    responseObserver.onNext(FileData.newBuilder()
                                            .setFilename(path.getFileName().toString())
                                            .setContent(com.google.protobuf.ByteString.copyFrom(content))
                                            .setMimeType(mimeType)
                                            .build());
                                } catch (IOException e) {
                                    logger.warn("Failed to read existing file: " + path, e);
                                }
                            });
                } catch (IOException e) {
                    logger.warn("Failed walking existing files", e);
                }
            }

            // Setup watch service for new or modified files
            WatchService watchService = FileSystems.getDefault().newWatchService();
            // Register directories recursively
            try {
                Files.walk(outputDir)
                        .filter(Files::isDirectory)
                        .forEach(dir -> {
                            try {
                                dir.register(watchService,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_MODIFY
                                );
                            } catch (IOException ignored) {}
                        });
            } catch (IOException e) {
                logger.error("Failed to register watch service", e);
                responseObserver.onError(e);
                try { watchService.close(); } catch (IOException ignored) {}
                return;
            }

            // Create and start watcher
            ResultWatcher resultWatcher = new ResultWatcher(
                    watcherId,
                    simulationId,
                    watchService,
                    patterns,
                    responseObserver
            );
            
            // Store watcher reference
            activeWatchers.put(watcherId, resultWatcher);
            
            // Start the watcher thread
            resultWatcher.start();
            
            // Set up cleanup when client disconnects
            io.grpc.Context.current().addListener(new io.grpc.Context.CancellationListener() {
                @Override
                public void cancelled(io.grpc.Context context) {
                    logger.info("Client disconnected, stopping watcher: " + watcherId);
                    ResultWatcher watcher = activeWatchers.remove(watcherId);
                    if (watcher != null) {
                        watcher.stop();
                    }
                }
            }, java.util.concurrent.Executors.newSingleThreadExecutor());
            
            logger.info("Started watcher " + watcherId + " for simulation " + simulationId);

            // Note: do not call onCompleted here; keep stream open until client cancels

        } catch (Exception e) {
            logger.error("Error in subscribeResults", e);
            responseObserver.onError(e);
        }
    }

//    public void setResultsService(ResultsServiceImpl resultsService) {
//        this.resultsService = resultsService;
//        if (this.resultsService != null) {
//            this.resultsService.setDefaultRootDirectory(this.workingDirectory);
//        }
//    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setInitialWorkingDirectory(String workingDirectory) {
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            this.workingDirectory = workingDirectory;
            if (resultsService != null) {
                resultsService.setDefaultRootDirectory(workingDirectory);
            }
        }
    }
    
    public void setUgPath(String ugPath) {
        this.ugPath = ugPath;
    }
    
    public String getUgPath() {
        return ugPath;
    }
    
    public Map<String, SimulationExecutor> getActiveSimulations() {
        return activeSimulations;
    }
    
    private boolean matchesPatterns(Path path, java.util.List<String> patterns) {
        if (patterns.isEmpty()) {
            return true;
        }
        String filename = path.getFileName().toString();
        return patterns.stream().anyMatch(pattern -> 
                filename.matches(pattern.replace("*", ".*")));
    }
    
    private String getStackTrace(Exception e) {
        return java.util.Arrays.stream(e.getStackTrace())
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n"));
    }
    
    private void stopWatchersForSimulation(String simulationId) {
        logger.info("Stopping all watchers for simulation: " + simulationId);
        
        // Find and stop all watchers for this simulation
        activeWatchers.entrySet().removeIf(entry -> {
            ResultWatcher watcher = entry.getValue();
            if (watcher.getSimulationId().equals(simulationId)) {
                logger.info("Stopping watcher: " + entry.getKey());
                watcher.stop();
                return true;
            }
            return false;
        });
    }
    
    // Inner class to manage individual result watchers
    private static class ResultWatcher {
        private static final Logger logger = LoggerFactory.getLogger(ResultWatcher.class);
        private final String watcherId;
        private final String simulationId;
        private final WatchService watchService;
        private final List<String> patterns;
        private final StreamObserver<FileData> responseObserver;
        private Thread watcherThread;
        private volatile boolean running = false;
        
        public ResultWatcher(String watcherId, String simulationId, WatchService watchService,
                           List<String> patterns, StreamObserver<FileData> responseObserver) {
            this.watcherId = watcherId;
            this.simulationId = simulationId;
            this.watchService = watchService;
            this.patterns = patterns;
            this.responseObserver = responseObserver;
        }
        
        public String getSimulationId() {
            return simulationId;
        }
        
        public void start() {
            running = true;
            watcherThread = new Thread(() -> {
                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        WatchKey key = watchService.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (key == null) continue;
                        
                        Path dir = (Path) key.watchable();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (!running) break;
                            if (!(event.context() instanceof Path)) continue;
                            
                            Path name = (Path) event.context();
                            Path child = dir.resolve(name);
                            
                            if (!Files.isRegularFile(child)) continue;
                            if (!matchesPatterns(child, patterns)) continue;
                            
                            try {
                                boolean ready = FileWriteDetector.waitUntilReady(child);
                                if (!ready) {
                                    logger.info("File not ready: " + child);
                                    continue;
                                }
                                
                                byte[] content = Files.readAllBytes(child);
                                String mimeType = Files.probeContentType(child);
                                if (mimeType == null) mimeType = "application/octet-stream";
                                
                                logger.info("Sending file via watcher " + watcherId + ": " + child.getFileName());
                                
                                synchronized(responseObserver) {
                                    responseObserver.onNext(FileData.newBuilder()
                                            .setFilename(child.getFileName().toString())
                                            .setContent(com.google.protobuf.ByteString.copyFrom(content))
                                            .setMimeType(mimeType)
                                            .build());
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to process file in watcher " + watcherId + ": " + child, e);
                            }
                        }
                        
                        boolean valid = key.reset();
                        if (!valid) {
                            logger.warn("Watch key invalid for watcher " + watcherId);
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    logger.info("Watcher " + watcherId + " interrupted");
                } finally {
                    try {
                        watchService.close();
                    } catch (IOException e) {
                        logger.warn("Error closing watch service for watcher " + watcherId, e);
                    }
                    logger.info("Watcher " + watcherId + " stopped");
                }
            }, "ResultWatcher-" + watcherId);
            
            watcherThread.setDaemon(true);
            watcherThread.start();
        }
        
        public void stop() {
            running = false;
            if (watcherThread != null) {
                watcherThread.interrupt();
                try {
                    watcherThread.join(1000);
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while stopping watcher " + watcherId);
                }
            }
        }
        
        private boolean matchesPatterns(Path path, List<String> patterns) {
            if (patterns.isEmpty()) {
                return true;
            }
            String filename = path.getFileName().toString();
            return patterns.stream().anyMatch(pattern -> 
                    filename.matches(pattern.replace("*", ".*")));
        }
    }


}

final class FileWriteDetector {

    private FileWriteDetector() {}

    /**
     * Quick, best-effort check.
     * On Windows: returns true if an exclusive (share-none) open fails with sharing violation.
     * Elsewhere: samples size/mtime twice over a short window; if changing, assumes "being written".
     */
    public static boolean isBeingWrittenNow(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        if (isWindows()) {
            WindowsExclusiveOpen.Result r = WindowsExclusiveOpen.tryOpenExclusive(file);
            if (r == WindowsExclusiveOpen.Result.SHARING_VIOLATION) return true;      // someone has it open
            if (r == WindowsExclusiveOpen.Result.SUCCESS) return false;                // no open handles => not busy
            // Other errors: fall through to heuristic (permissions, etc.)
        }
        // Portable heuristic: if size/mtime change within probe window, assume "busy"
        return !hasBeenQuietFor(file, Duration.ofMillis(600), Duration.ofMillis(650), Duration.ofMillis(150));
    }

    /**
     * Wait until a file has been "quiet" (no size/mtime changes and no watch events)
     * for at least {@code quietFor}. Returns true if it became ready before {@code timeout}.
     * Works on Linux, macOS, and Windows. Uses WatchService + polling fallback.
     */
    public static boolean waitUntilReady(Path file,
                                         Duration quietFor,
                                         Duration timeout,
                                         Duration pollInterval) throws IOException, InterruptedException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(quietFor, "quietFor");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");

        System.out.println("Waiting for " + file + ":");

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            // You can choose to wait for creation instead; here we require it to exist.
            throw new NoSuchFileException(file.toString());
        }

        final Path dir = file.getParent();
        final String targetName = file.getFileName().toString();

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long stableSince = System.nanoTime();

        Snapshot last = Snapshot.take(file);

        try (WatchService ws = dir.getFileSystem().newWatchService()) {
            dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

            while (true) {

                System.out.println(" -> Waiting for " + file + " to become ready...");

                // 1) React to FS events (if any)
                long remainingPollMs = Math.max(1L, Math.min(pollInterval.toMillis(),
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadlineNanos - System.nanoTime()))));

                WatchKey key = ws.poll(remainingPollMs, TimeUnit.MILLISECONDS);
                if (key != null) {
                    for (WatchEvent<?> evt : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = evt.kind();
                        if (kind == OVERFLOW) continue;
                        @SuppressWarnings("unchecked")
                        Path ctx = ((WatchEvent<Path>) evt).context();
                        if (ctx != null && ctx.getFileName().toString().equals(targetName)) {
                            // Any visible change to our file restarts the quiet timer
                            stableSince = System.nanoTime();
                            // If it was deleted, we can decide to return false (not ready).
                            if (kind == ENTRY_DELETE && !Files.exists(file)) {
                                return false;
                            }
                        }
                    }
                    key.reset();
                }

                // 2) Check snapshot stability (size + mtime)
                Snapshot now = Snapshot.take(file);
                if (!now.exists) {
                    // File disappeared during wait; treat as not ready.
                    return false;
                }
                if (!now.equals(last)) {
                    last = now;
                    stableSince = System.nanoTime();
                }

                // 3) Windows fast-path: if no one has the file open AND quietFor elapsed, return true
                if (isWindows()) {
                    WindowsExclusiveOpen.Result r = WindowsExclusiveOpen.tryOpenExclusive(file);
                    if (r == WindowsExclusiveOpen.Result.SUCCESS
                            && elapsed(stableSince) >= quietFor.toNanos()) {
                        return true;
                    }
                    // If sharing violation => likely being written; keep waiting.
                }

                // 4) Portable readiness: quiet period elapsed
                if (elapsed(stableSince) >= quietFor.toNanos()) {
                    return true;
                }

                // 5) Timeout?
                if (System.nanoTime() > deadlineNanos) {
                    return false;
                }
            }
        }
    }

    /** Convenience overload with sensible defaults: quietFor=2s, timeout=2min, poll=200ms */
    public static boolean waitUntilReady(Path file) throws IOException, InterruptedException {
        return waitUntilReady(file, Duration.ofMillis(100), Duration.ofMinutes(2), Duration.ofMillis(10));
    }

    // ---- internals ---------------------------------------------------------------------------

    private static boolean hasBeenQuietFor(Path file, Duration quietFor, Duration window, Duration step) throws IOException {
        long stableSince = System.nanoTime();
        Snapshot last = Snapshot.take(file);
        long end = System.nanoTime() + window.toNanos();

        while (System.nanoTime() < end) {
            sleep(step);
            Snapshot now = Snapshot.take(file);
            if (!now.exists) return false;
            if (!now.equals(last)) {
                last = now;
                stableSince = System.nanoTime();
            }
            if (elapsed(stableSince) >= quietFor.toNanos()) return true;
        }
        return elapsed(stableSince) >= quietFor.toNanos();
    }

    private static long elapsed(long sinceNano) {
        return System.nanoTime() - sinceNano;
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(Math.max(1, d.toMillis())); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static final class Snapshot {
        final boolean exists;
        final long size;
        final long mtimeNanos;

        private Snapshot(boolean exists, long size, long mtimeNanos) {
            this.exists = exists; this.size = size; this.mtimeNanos = mtimeNanos;
        }

        static Snapshot take(Path p) throws IOException {
            if (!Files.exists(p)) return new Snapshot(false, -1L, -1L);
            long size = Files.size(p);
            FileTime ft = Files.getLastModifiedTime(p);
            return new Snapshot(true, size, ft.toMillis() * 1_000_000L); // coarse but portable
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Snapshot)) return false;
            Snapshot s = (Snapshot) o;
            return exists == s.exists && size == s.size && mtimeNanos == s.mtimeNanos;
        }

        @Override public int hashCode() { return (exists ? 1 : 0) * 31 + Long.hashCode(size) * 17 + Long.hashCode(mtimeNanos); }
    }

    // ------------------------ Windows exclusive-open check (via JNA) ---------------------------
    // If you don't want the JNA dependency, you can stub WindowsExclusiveOpen.tryOpenExclusive()
    // to return Result.UNKNOWN and the utility will rely purely on the stability heuristic.

    private static final class WindowsExclusiveOpen {
        enum Result { SUCCESS, SHARING_VIOLATION, OTHER_ERROR, UNKNOWN }

        static Result tryOpenExclusive(Path path) {
            if (!isWindows()) return Result.UNKNOWN;
            try {
                return Kernel32Exclusive.tryOpen(path);
            } catch (Throwable t) {
                // If JNA missing or any unexpected issue, fall back to heuristic path.
                return Result.UNKNOWN;
            }
        }

        // Minimal JNA surface
        private static final class Kernel32Exclusive {
//            // ---- JNA bits (compile-time dependency: net.java.dev.jna:jna) ----
//            interface Kernel32 extends com.sun.jna.Library {
//                Kernel32 INSTANCE = com.sun.jna.Native.load("kernel32", Kernel32.class);
//
//                com.sun.jna.ptr.IntByReference GetLastError(); // not used directly; we use Native.getLastError()
//
//                com.sun.jna.platform.win32.WinNT.HANDLE CreateFileW(
//                        char[] lpFileName,
//                        int dwDesiredAccess,
//                        int dwShareMode,
//                        com.sun.jna.Pointer lpSecurityAttributes,
//                        int dwCreationDisposition,
//                        int dwFlagsAndAttributes,
//                        com.sun.jna.platform.win32.WinNT.HANDLE hTemplateFile
//                );
//
//                boolean CloseHandle(com.sun.jna.platform.win32.WinNT.HANDLE hObject);
//            }

            // constants
            private static final int GENERIC_READ = 0x80000000;
            private static final int FILE_SHARE_NONE = 0x0;
            private static final int OPEN_EXISTING = 3;
            private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;

            private static final int ERROR_SHARING_VIOLATION = 32;

            static Result tryOpen(Path path) {
//                char[] name = (path.toAbsolutePath().toString() + "\0").toCharArray();
//                com.sun.jna.platform.win32.WinNT.HANDLE h = Kernel32.INSTANCE.CreateFileW(
//                        name,
//                        GENERIC_READ,
//                        FILE_SHARE_NONE,           // <— No sharing allowed
//                        com.sun.jna.Pointer.NULL,
//                        OPEN_EXISTING,
//                        FILE_ATTRIBUTE_NORMAL,
//                        com.sun.jna.platform.win32.WinNT.HANDLE.NULL
//                );
//
//                int lastErr = com.sun.jna.Native.getLastError();
//                if (h == null || com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(h)) {
//                    if (lastErr == ERROR_SHARING_VIOLATION) return Result.SHARING_VIOLATION;
//                    return Result.OTHER_ERROR;
//                } else {
//                    Kernel32.INSTANCE.CloseHandle(h);
//                    return Result.SUCCESS;
//                }

                return Result.UNKNOWN;
            }
        }
    }
}
