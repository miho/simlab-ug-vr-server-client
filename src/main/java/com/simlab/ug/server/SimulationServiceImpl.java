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

public class SimulationServiceImpl extends SimulationServiceGrpc.SimulationServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(SimulationServiceImpl.class);
    
    private final Map<String, SimulationExecutor> activeSimulations = new ConcurrentHashMap<>();
    private final Map<String, String> completedSimulationDirs = new ConcurrentHashMap<>();
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
                    request.getUgExecutable(),
                    request.getParametersList(),
                    workingDirectory,
                    request.getOutputDirectory()
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
                            logger.info("Sending file: " + path.getFileName());
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
        try {
            String simulationId = request.getSimulationId();
            java.util.List<String> patterns = request.getFilePatternsList();
            boolean includeExisting = request.getIncludeExisting();

            // Resolve output directory similar to getSimulationResults
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

            final WatchService ws = watchService;
            Thread watcher = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        WatchKey key = ws.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (key == null) continue;
                        Path dir = (Path) key.watchable();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (!(event.context() instanceof Path)) continue;
                            Path name = (Path) event.context();
                            Path child = dir.resolve(name);
                            if (!Files.isRegularFile(child)) continue;
                            if (!matchesPatterns(child, patterns)) continue;

                            // check whether file is ready (done being written by other program)
                            while (Files.exists(child) && !Files.isWritable(child)) {
                                Thread.sleep(100);
                            }

                            try {
                                byte[] content = Files.readAllBytes(child);
                                String mimeType = Files.probeContentType(child);
                                if (mimeType == null) mimeType = "application/octet-stream";
                                responseObserver.onNext(FileData.newBuilder()
                                        .setFilename(child.getFileName().toString())
                                        .setContent(com.google.protobuf.ByteString.copyFrom(content))
                                        .setMimeType(mimeType)
                                        .build());
                            } catch (IOException e) {
                                logger.warn("Failed to read changed file: " + child, e);
                            }
                        }
                        boolean valid = key.reset();
                        if (!valid) break;
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    try { ws.close(); } catch (IOException ignored) {}
                }
            }, "SubscribeResultsWatcher");
            watcher.setDaemon(true);
            watcher.start();

            // Note: do not call onCompleted here; keep stream open until client cancels

        } catch (Exception e) {
            logger.error("Error in subscribeResults", e);
            responseObserver.onError(e);
        }
    }

    public void setResultsService(ResultsServiceImpl resultsService) {
        this.resultsService = resultsService;
        if (this.resultsService != null) {
            this.resultsService.setDefaultRootDirectory(this.workingDirectory);
        }
    }

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
}