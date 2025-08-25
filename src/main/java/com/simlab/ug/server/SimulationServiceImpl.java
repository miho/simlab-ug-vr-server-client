package com.simlab.ug.server;

import com.simlab.ug.grpc.*;
import com.simlab.ug.common.LuaScriptParser;
import com.simlab.ug.common.SimulationExecutor;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SimulationServiceImpl extends SimulationServiceGrpc.SimulationServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(SimulationServiceImpl.class);
    
    private final Map<String, SimulationExecutor> activeSimulations = new ConcurrentHashMap<>();
    private String ugPath = "";
    private String workingDirectory = System.getProperty("user.dir");
    private final LuaScriptParser scriptParser = new LuaScriptParser();
    
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
            Path outputDir = Paths.get(workingDirectory, "output", request.getSimulationId());
            if (!Files.exists(outputDir)) {
                responseObserver.onCompleted();
                return;
            }
            
            Files.walk(outputDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesPatterns(path, request.getFilePatternsList()))
                    .forEach(path -> {
                        try {
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
        } catch (Exception e) {
            logger.error("Error getting simulation results", e);
            responseObserver.onError(e);
        }
    }
    
    public void setUgPath(String ugPath) {
        this.ugPath = ugPath;
    }
    
    public String getUgPath() {
        return ugPath;
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