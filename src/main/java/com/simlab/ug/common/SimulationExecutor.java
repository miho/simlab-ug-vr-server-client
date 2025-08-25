package com.simlab.ug.common;

import com.simlab.ug.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SimulationExecutor {
    private static final Logger logger = LoggerFactory.getLogger(SimulationExecutor.class);
    
    private final String simulationId;
    private final String scriptPath;
    private final String ugExecutable;
    private final List<ParameterValue> parameters;
    private final String workingDirectory;
    private final String outputDirectory;
    
    private Process process;
    private SimulationState state = SimulationState.PENDING;
    private double progress = 0.0;
    private long startTime;
    
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "(?:Progress|Step|Iteration|Refinement)[:\\s]*(\\d+)(?:\\s*/\\s*(\\d+))?",
            Pattern.CASE_INSENSITIVE
    );
    
    public interface UpdateListener {
        void onProgress(double percentage, String message, int current, int total);
        void onLog(LogLevel level, String message);
        void onComplete(SimulationState state, long duration, List<String> outputFiles);
        void onError(String error, String stackTrace);
    }
    
    public SimulationExecutor(String simulationId, String scriptPath, String ugExecutable,
                             List<ParameterValue> parameters, String workingDirectory, 
                             String outputDirectory) {
        this.simulationId = simulationId;
        this.scriptPath = scriptPath;
        this.ugExecutable = ugExecutable;
        this.parameters = parameters;
        this.workingDirectory = workingDirectory;
        this.outputDirectory = outputDirectory != null ? outputDirectory : 
                Paths.get(workingDirectory, "output", simulationId).toString();
    }
    
    public void execute(UpdateListener listener) {
        CompletableFuture.runAsync(() -> {
            try {
                startTime = System.currentTimeMillis();
                state = SimulationState.RUNNING;
                
                // Create output directory
                Path outDir = Paths.get(outputDirectory);
                Files.createDirectories(outDir);
                
                // Build command
                List<String> command = buildCommand();
                
                listener.onLog(LogLevel.INFO, "Starting simulation with command: " + 
                        String.join(" ", command));
                
                // Start process
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(new File(workingDirectory));
                pb.redirectErrorStream(false);
                
                // Set environment to ensure output directory is used
                pb.environment().put("OUTPUT_DIR", outputDirectory);
                
                process = pb.start();
                
                // Read output streams
                Thread outputReader = new Thread(() -> 
                        readStream(process.getInputStream(), listener, false));
                Thread errorReader = new Thread(() -> 
                        readStream(process.getErrorStream(), listener, true));
                
                outputReader.start();
                errorReader.start();
                
                // Wait for process to complete
                boolean completed = process.waitFor(24, TimeUnit.HOURS);
                
                if (completed) {
                    int exitCode = process.exitValue();
                    if (exitCode == 0) {
                        state = SimulationState.COMPLETED;
                        long duration = System.currentTimeMillis() - startTime;
                        List<String> outputFiles = findOutputFiles();
                        listener.onComplete(state, duration, outputFiles);
                    } else {
                        state = SimulationState.FAILED;
                        listener.onError("Process exited with code: " + exitCode, "");
                    }
                } else {
                    state = SimulationState.FAILED;
                    process.destroyForcibly();
                    listener.onError("Simulation timed out after 24 hours", "");
                }
                
            } catch (Exception e) {
                state = SimulationState.FAILED;
                logger.error("Simulation execution error", e);
                listener.onError(e.getMessage(), getStackTrace(e));
            }
        });
    }
    
    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(ugExecutable);
        command.add("-ex");
        command.add(scriptPath);
        
        // Add parameters
        for (ParameterValue param : parameters) {
            command.add(param.getName());
            
            if (param.hasStringValue()) {
                command.add(param.getStringValue());
            } else if (param.hasIntValue()) {
                command.add(String.valueOf(param.getIntValue()));
            } else if (param.hasFloatValue()) {
                command.add(String.valueOf(param.getFloatValue()));
            } else if (param.hasBoolValue()) {
                command.add(String.valueOf(param.getBoolValue()));
            } else if (param.hasArrayValue()) {
                command.addAll(param.getArrayValue().getValuesList());
            }
        }
        
        // Override output directory parameter if present
        command.add("-outputDir");
        command.add(outputDirectory);
        
        return command;
    }
    
    private void readStream(InputStream stream, UpdateListener listener, boolean isError) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            int currentStep = 0;
            int totalSteps = 0;
            
            while ((line = reader.readLine()) != null) {
                // Log the line
                LogLevel level = isError ? LogLevel.LOG_ERROR : LogLevel.INFO;
                if (line.toLowerCase().contains("warning")) {
                    level = LogLevel.WARNING;
                }
                listener.onLog(level, line);
                
                // Try to parse progress
                Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
                if (progressMatcher.find()) {
                    currentStep = Integer.parseInt(progressMatcher.group(1));
                    if (progressMatcher.group(2) != null) {
                        totalSteps = Integer.parseInt(progressMatcher.group(2));
                        progress = (double) currentStep / totalSteps;
                        listener.onProgress(progress * 100, line, currentStep, totalSteps);
                    } else {
                        listener.onProgress(progress * 100, line, currentStep, totalSteps);
                    }
                }
                
                // Check for specific UG4 progress indicators
                if (line.contains("Refining grid")) {
                    String refinementInfo = extractRefinementInfo(line);
                    if (refinementInfo != null) {
                        listener.onProgress(progress * 100, refinementInfo, currentStep, totalSteps);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error reading process stream", e);
        }
    }
    
    private String extractRefinementInfo(String line) {
        Pattern refPattern = Pattern.compile("level\\s+(\\d+)");
        Matcher matcher = refPattern.matcher(line);
        if (matcher.find()) {
            return "Grid refinement level " + matcher.group(1);
        }
        return null;
    }
    
    private List<String> findOutputFiles() {
        List<String> files = new ArrayList<>();
        try {
            Path outDir = Paths.get(outputDirectory);
            if (Files.exists(outDir)) {
                files = Files.walk(outDir)
                        .filter(Files::isRegularFile)
                        .map(Path::toString)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            logger.error("Error finding output files", e);
        }
        return files;
    }
    
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
            state = SimulationState.CANCELLED;
        }
    }
    
    public SimulationState getState() {
        return state;
    }
    
    public double getProgress() {
        return progress;
    }
    
    public String getScriptPath() {
        return scriptPath;
    }
    
    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}