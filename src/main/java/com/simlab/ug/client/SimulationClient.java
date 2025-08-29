package com.simlab.ug.client;

import com.simlab.ug.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import io.grpc.Context;
import io.grpc.Context.CancellableContext;

public class SimulationClient {
    private static final Logger logger = LoggerFactory.getLogger(SimulationClient.class);
    
    private ManagedChannel channel;
    private SimulationServiceGrpc.SimulationServiceBlockingStub blockingStub;
    private SimulationServiceGrpc.SimulationServiceStub asyncStub;
    
    public interface SimulationListener {
        void onProgress(double percentage, String message, int current, int total);
        void onLog(LogLevel level, String message, long timestamp);
        void onResult(SimulationResult result);
        void onError(String error, String stackTrace);
        void onComplete();
    }
    
    public SimulationClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = SimulationServiceGrpc.newBlockingStub(channel);
        this.asyncStub = SimulationServiceGrpc.newStub(channel);
    }
    
    public ServerStatus getServerStatus() {
        try {
            return blockingStub.getServerStatus(Empty.newBuilder().build());
        } catch (StatusRuntimeException e) {
            logger.error("Failed to get server status", e);
            throw new RuntimeException("Failed to connect to server: " + e.getMessage());
        }
    }
    
    public boolean setWorkingDirectory(String directory) {
        try {
            StatusResponse response = blockingStub.setWorkingDirectory(
                    SetWorkingDirectoryRequest.newBuilder()
                            .setDirectory(directory)
                            .build()
            );
            return response.getSuccess();
        } catch (StatusRuntimeException e) {
            logger.error("Failed to set working directory", e);
            return false;
        }
    }
    
    public ScriptAnalysis analyzeScript(String scriptPath) {
        try {
            return blockingStub.analyzeScript(
                    AnalyzeScriptRequest.newBuilder()
                            .setScriptPath(scriptPath)
                            .build()
            );
        } catch (StatusRuntimeException e) {
            logger.error("Failed to analyze script", e);
            return ScriptAnalysis.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("Failed to analyze script: " + e.getMessage())
                    .build();
        }
    }
    
    public void runSimulation(String simulationId, String scriptPath,
                             List<ParameterValue> parameters,
                             SimulationListener listener) {
        
        RunSimulationRequest request = RunSimulationRequest.newBuilder()
                .setSimulationId(simulationId)
                .setScriptPath(scriptPath)
                .addAllParameters(parameters)
                .build();
        
        asyncStub.runSimulation(request, new StreamObserver<SimulationUpdate>() {
            @Override
            public void onNext(SimulationUpdate update) {
                switch (update.getType()) {
                    case PROGRESS:
                        ProgressUpdate progress = update.getProgress();
                        listener.onProgress(
                                progress.getPercentage(),
                                progress.getMessage(),
                                progress.getCurrentStep(),
                                progress.getTotalSteps()
                        );
                        break;
                        
                    case LOG:
                        LogMessage log = update.getLog();
                        listener.onLog(
                                log.getLevel(),
                                log.getMessage(),
                                log.getTimestamp()
                        );
                        break;
                        
                    case RESULT:
                        listener.onResult(update.getResult());
                        break;
                        
                    case UPDATE_ERROR:
                        ErrorMessage error = update.getError();
                        listener.onError(error.getError(), error.getStackTrace());
                        break;
                }
            }
            
            @Override
            public void onError(Throwable t) {
                logger.error("Simulation stream error", t);
                listener.onError("Stream error: " + t.getMessage(), "");
            }
            
            @Override
            public void onCompleted() {
                listener.onComplete();
            }
        });
    }
    
    public boolean stopSimulation(String simulationId) {
        try {
            StatusResponse response = blockingStub.stopSimulation(
                    StopSimulationRequest.newBuilder()
                            .setSimulationId(simulationId)
                            .build()
            );
            return response.getSuccess();
        } catch (StatusRuntimeException e) {
            logger.error("Failed to stop simulation", e);
            return false;
        }
    }
    
    public void getSimulationResults(String simulationId, List<String> filePatterns,
                                    Consumer<FileData> fileHandler, Runnable onComplete) {
        GetResultsRequest request = GetResultsRequest.newBuilder()
                .setSimulationId(simulationId)
                .addAllFilePatterns(filePatterns)
                .build();
        
        asyncStub.getSimulationResults(request, new StreamObserver<FileData>() {
            @Override
            public void onNext(FileData fileData) {
                fileHandler.accept(fileData);
            }
            
            @Override
            public void onError(Throwable t) {
                logger.error("Error getting simulation results", t);
            }
            
            @Override
            public void onCompleted() {
                onComplete.run();
            }
        });
    }
    
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("Error shutting down client", e);
        }
    }
    
    public boolean isConnected() {
        try {
            getServerStatus();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public CancellableContext subscribeResults(String simulationId, List<String> filePatterns, boolean includeExisting,
                                 Consumer<FileData> fileHandler, Consumer<Throwable> onError) {
        SubscribeResultsRequest request = SubscribeResultsRequest.newBuilder()
                .setSimulationId(simulationId)
                .addAllFilePatterns(filePatterns)
                .setIncludeExisting(includeExisting)
                .build();

        // Create a cancellable context for this subscription
        CancellableContext context = Context.current().withCancellation();
        
        context.run(() -> {
            asyncStub.subscribeResults(request, new io.grpc.stub.StreamObserver<FileData>() {
                @Override
                public void onNext(FileData value) {
                    fileHandler.accept(value);
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("subscribeResults error", t);
                    if (onError != null) onError.accept(t);
                }

                @Override
                public void onCompleted() {
                    logger.info("subscribeResults completed for simulation: {}", simulationId);
                    // keep-alive stream typically doesn't complete; noop
                }
            });
        });
        
        return context;
    }
}