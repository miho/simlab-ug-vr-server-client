package com.simlab.ug.client;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Represents a group of VTU files with similar names, potentially forming a time series
 */
public class VtuFileGroup {
    private String groupName;
    private String pattern;
    private List<VtuFile> files = new ArrayList<>();
    private Map<String, String> conversionOptions = new HashMap<>();
    private boolean isTimeSeries = false;
    
    // Pattern to detect time step in filename (e.g., t00001.vtu or _t00001.vtu)
    private static final Pattern TIME_STEP_PATTERN = Pattern.compile(".*[_\\.]?t(\\d+)\\.vtu$", Pattern.CASE_INSENSITIVE);
    
    public static class VtuFile {
        private String filename;
        private String fullPath;
        private Integer timeStep;
        private long fileSize;
        private Date lastModified;
        
        public VtuFile(String filename, String fullPath) {
            this.filename = filename;
            this.fullPath = fullPath;
            this.timeStep = extractTimeStep(filename);
            
            File file = new File(fullPath);
            if (file.exists()) {
                this.fileSize = file.length();
                this.lastModified = new Date(file.lastModified());
            }
        }
        
        private Integer extractTimeStep(String filename) {
            Matcher matcher = TIME_STEP_PATTERN.matcher(filename);
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
        
        // Getters
        public String getFilename() { return filename; }
        public String getFullPath() { return fullPath; }
        public Integer getTimeStep() { return timeStep; }
        public long getFileSize() { return fileSize; }
        public Date getLastModified() { return lastModified; }
        public boolean hasTimeStep() { return timeStep != null; }
    }
    
    public VtuFileGroup(String groupName, String pattern) {
        this.groupName = groupName;
        this.pattern = pattern;
        setDefaultConversionOptions();
    }
    
    private void setDefaultConversionOptions() {
        // Default options for conversion
        conversionOptions.put("export", "scene.gltf");
        conversionOptions.put("bg", "0.08,0.09,0.10");
        conversionOptions.put("opacity", "1.0");
    }
    
    public void addFile(VtuFile file) {
        files.add(file);
        // Check if this is a time series
        updateTimeSeriesStatus();
    }
    
    private void updateTimeSeriesStatus() {
        long filesWithTimeStep = files.stream()
                .filter(VtuFile::hasTimeStep)
                .count();
        isTimeSeries = filesWithTimeStep > 0 && filesWithTimeStep == files.size();
    }
    
    public void sortFilesByTimeStep() {
        if (isTimeSeries) {
            files.sort(Comparator.comparing(VtuFile::getTimeStep));
        } else {
            files.sort(Comparator.comparing(VtuFile::getFilename));
        }
    }
    
    public List<VtuFile> getFilesForTimeStep(int timeStep) {
        if (!isTimeSeries) return new ArrayList<>();
        return files.stream()
                .filter(f -> f.getTimeStep() != null && f.getTimeStep() == timeStep)
                .collect(java.util.stream.Collectors.toList());
    }
    
    public Set<Integer> getAvailableTimeSteps() {
        return files.stream()
                .map(VtuFile::getTimeStep)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }
    
    // Build command line arguments for vtu2gltf
    public List<String> buildConversionCommand(String vtu2gltfPath, List<VtuFile> selectedFiles) {
        List<String> command = new ArrayList<>();
        command.add(vtu2gltfPath);
        
        // Add input files
        for (VtuFile file : selectedFiles) {
            command.add(file.getFullPath());
        }
        
        // Add conversion options
        for (Map.Entry<String, String> option : conversionOptions.entrySet()) {
            String key = option.getKey();
            String value = option.getValue();
            
            if (value != null && !value.isEmpty()) {
                if (key.equals("no-preview") || key.equals("contour")) {
                    // Boolean flags
                    if ("true".equalsIgnoreCase(value)) {
                        command.add("--" + key);
                    }
                } else {
                    // Key-value options
                    command.add("--" + key + "=" + value);
                }
            }
        }
        
        return command;
    }
    
    // Getters and setters
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    
    public List<VtuFile> getFiles() { return files; }
    
    public boolean isTimeSeries() { return isTimeSeries; }
    
    public Map<String, String> getConversionOptions() { return conversionOptions; }
    
    public void setConversionOption(String key, String value) {
        conversionOptions.put(key, value);
    }
    
    public String getConversionOption(String key) {
        return conversionOptions.get(key);
    }
    
    public int getFileCount() { return files.size(); }
    
    public void clearFiles() {
        files.clear();
        isTimeSeries = false;
    }
}