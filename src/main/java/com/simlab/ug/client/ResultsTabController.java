package com.simlab.ug.client;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.Scene;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.jpro.webapi.WebAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Controller for the enhanced Results tab with VTU grouping and GLTF conversion
 */
public class ResultsTabController {
    private static final Logger logger = LoggerFactory.getLogger(ResultsTabController.class);
    
    private VBox root;
    private TextField outputDirField;
    private TextField vtu2gltfPathField;
    private TableView<VtuFileGroup> groupsTable;
    private ObservableList<VtuFileGroup> fileGroups = FXCollections.observableArrayList();
    private TextArea conversionLogArea;
    private ProgressBar conversionProgress;
    private Label statusLabel;
    
    private Path currentOutputDirectory;
    private WatchService watchService;
    private Thread watchThread;
    private boolean isWatching = false;
    
    // Configuration
    private String vtu2gltfExecutable = "";
    
    // Throttling for conversion logs (better in JPro)
    private final ConcurrentLinkedQueue<String> pendingConversionLogs = new ConcurrentLinkedQueue<>();
    private volatile boolean conversionFlushScheduled = false;
    private static final ScheduledExecutorService uiBatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-batch-results");
        t.setDaemon(true);
        return t;
    });
    private long lastStatusUiUpdateMs = 0L;
    
    public VBox createResultsTab() {
        root = new VBox(10);
        root.setPadding(new Insets(10));
        
        // Output directory selection
        HBox outputDirBox = createOutputDirectorySection();
        
        // VTU2GLTF tool configuration
        HBox toolConfigBox = createToolConfigSection();
        
        // File groups section
        VBox groupsSection = createGroupsSection();
        
        // Conversion log
        VBox logSection = createLogSection();
        
        root.getChildren().addAll(
            outputDirBox,
            toolConfigBox,
            new Separator(),
            groupsSection,
            new Separator(),
            logSection
        );
        
        return root;
    }
    
    private HBox createOutputDirectorySection() {
        HBox box = new HBox(10);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        Label label = new Label("Output Directory:");
        outputDirField = new TextField();
        outputDirField.setPrefWidth(400);
        // Keep currentOutputDirectory in sync when user types
        outputDirField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                Path p = Paths.get(newVal);
                if (Files.exists(p)) {
                    currentOutputDirectory = p;
                }
            }
        });
        
        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> browseForOutputDirectory());
        
        Button scanBtn = new Button("Scan for VTU Files");
        scanBtn.setOnAction(e -> scanForVtuFiles());
        
        CheckBox watchCheck = new CheckBox("Auto-monitor");
        watchCheck.setTooltip(new Tooltip("Automatically monitor directory for new VTU files"));
        watchCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                startWatching();
            } else {
                stopWatching();
            }
        });
        
        box.getChildren().addAll(label, outputDirField, browseBtn, scanBtn, watchCheck);
        return box;
    }
    
    private HBox createToolConfigSection() {
        HBox box = new HBox(10);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        Label label = new Label("VTU2GLTF Tool:");
        vtu2gltfPathField = new TextField();
        vtu2gltfPathField.setPrefWidth(400);
        vtu2gltfPathField.setPromptText("Path to vtu2gltf.exe");
        // Keep vtu2gltfExecutable in sync when user types
        vtu2gltfPathField.textProperty().addListener((obs, oldVal, newVal) -> {
            vtu2gltfExecutable = newVal != null ? newVal : "";
        });
        
        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> browseForVtu2gltf());
        
        Button testBtn = new Button("Test");
        testBtn.setOnAction(e -> testVtu2gltfTool());
        
        box.getChildren().addAll(label, vtu2gltfPathField, browseBtn, testBtn);
        return box;
    }
    
    private VBox createGroupsSection() {
        VBox section = new VBox(10);
        
        Label titleLabel = new Label("VTU File Groups");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
        
        // Controls for groups
        HBox controlBox = new HBox(10);
        
        Button addGroupBtn = new Button("Add Group");
        addGroupBtn.setOnAction(e -> addNewGroup());
        
        Button editGroupBtn = new Button("Edit Group");
        editGroupBtn.setOnAction(e -> editSelectedGroup());
        
        Button removeGroupBtn = new Button("Remove Selected");
        removeGroupBtn.setOnAction(e -> removeSelectedGroup());
        
        Button configureBtn = new Button("Configure Conversion");
        configureBtn.setOnAction(e -> configureSelectedGroup());
        
        Button convertBtn = new Button("Convert Selected");
        convertBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        convertBtn.setOnAction(e -> convertSelectedGroups());
        
        Button convertAllBtn = new Button("Convert All");
        convertAllBtn.setOnAction(e -> convertAllGroups());
        
        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setStyle("-fx-font-size: 11;");
        selectAllBtn.setOnAction(e -> {
            fileGroups.forEach(g -> g.setSelected(true));
            groupsTable.refresh();
        });
        
        Button deselectAllBtn = new Button("Deselect All");
        deselectAllBtn.setStyle("-fx-font-size: 11;");
        deselectAllBtn.setOnAction(e -> {
            fileGroups.forEach(g -> g.setSelected(false));
            groupsTable.refresh();
        });
        
        controlBox.getChildren().addAll(
            selectAllBtn, deselectAllBtn, new Separator(),
            addGroupBtn, editGroupBtn, removeGroupBtn, configureBtn, 
            new Separator(), convertBtn, convertAllBtn);
        
        // Table for file groups
        groupsTable = createGroupsTable();
        
        section.getChildren().addAll(titleLabel, controlBox, groupsTable);
        return section;
    }
    
    private TableView<VtuFileGroup> createGroupsTable() {
        TableView<VtuFileGroup> table = new TableView<>();
        table.setPrefHeight(200);
        table.setItems(fileGroups);
        
        // Checkbox column for selection
        TableColumn<VtuFileGroup, Boolean> selectCol = new TableColumn<>("");
        selectCol.setPrefWidth(30);
        selectCol.setCellValueFactory(cellData -> {
            VtuFileGroup group = cellData.getValue();
            javafx.beans.property.BooleanProperty property = 
                new javafx.beans.property.SimpleBooleanProperty(group.isSelected());
            
            property.addListener((obs, oldVal, newVal) -> {
                group.setSelected(newVal);
            });
            
            return property;
        });
        selectCol.setCellFactory(col -> new TableCell<VtuFileGroup, Boolean>() {
            private final CheckBox checkBox = new CheckBox();
            
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    VtuFileGroup group = getTableRow().getItem();
                    checkBox.setSelected(group.isSelected());
                    checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                        group.setSelected(newVal);
                    });
                    setGraphic(checkBox);
                }
            }
        });
        
        // Group name column
        TableColumn<VtuFileGroup, String> nameCol = new TableColumn<>("Group Name");
        nameCol.setPrefWidth(150);
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getGroupName()));
        
        // Pattern column
        TableColumn<VtuFileGroup, String> patternCol = new TableColumn<>("Pattern");
        patternCol.setPrefWidth(150);
        patternCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getPattern()));
        
        // File count column
        TableColumn<VtuFileGroup, String> countCol = new TableColumn<>("Files");
        countCol.setPrefWidth(60);
        countCol.setCellValueFactory(data -> 
            new SimpleStringProperty(String.valueOf(data.getValue().getFileCount())));
        
        // Time series column
        TableColumn<VtuFileGroup, String> timeSeriesCol = new TableColumn<>("Time Series");
        timeSeriesCol.setPrefWidth(80);
        timeSeriesCol.setCellValueFactory(data -> 
            new SimpleStringProperty(data.getValue().isTimeSeries() ? "Yes" : "No"));
        
        // Visualization type column
        TableColumn<VtuFileGroup, String> visCol = new TableColumn<>("Visualization");
        visCol.setPrefWidth(150);
        visCol.setCellValueFactory(data -> {
            VtuFileGroup group = data.getValue();
            String vis = "Surface";
            if ("true".equals(group.getConversionOption("contour"))) {
                vis = "Contour";
            } else if (group.getConversionOption("glyph-array") != null && 
                      !group.getConversionOption("glyph-array").isEmpty()) {
                vis = "Glyphs";
            } else if (group.getConversionOption("color-array") != null && 
                      !group.getConversionOption("color-array").isEmpty()) {
                vis = "Colored Surface";
            }
            return new SimpleStringProperty(vis);
        });
        
        table.getColumns().addAll(selectCol, nameCol, patternCol, countCol, timeSeriesCol, visCol);
        
        // Double-click to configure
        table.setRowFactory(tv -> {
            TableRow<VtuFileGroup> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    configureGroup(row.getItem());
                }
            });
            return row;
        });
        
        return table;
    }
    
    private VBox createLogSection() {
        VBox section = new VBox(5);
        
        Label titleLabel = new Label("Conversion Log");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
        
        conversionLogArea = new TextArea();
        conversionLogArea.setEditable(false);
        conversionLogArea.setPrefRowCount(8);
        conversionLogArea.setWrapText(true);
        
        HBox progressBox = new HBox(10);
        progressBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        conversionProgress = new ProgressBar(0);
        conversionProgress.setPrefWidth(200);
        
        statusLabel = new Label("Ready");
        
        Button clearLogBtn = new Button("Clear Log");
        clearLogBtn.setOnAction(e -> conversionLogArea.clear());
        
        progressBox.getChildren().addAll(conversionProgress, statusLabel, clearLogBtn);
        
        section.getChildren().addAll(titleLabel, conversionLogArea, progressBox);
        return section;
    }
    
    private void browseForOutputDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");
        
        File dir = chooser.showDialog(root.getScene().getWindow());
        if (dir != null) {
            outputDirField.setText(dir.getAbsolutePath());
            currentOutputDirectory = dir.toPath();
        }
    }
    
    private void browseForVtu2gltf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select vtu2gltf Executable");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Executable", "*", "*")
        );
        
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file != null) {
            vtu2gltfPathField.setText(file.getAbsolutePath());
            vtu2gltfExecutable = file.getAbsolutePath();
        }
    }
    
    private void testVtu2gltfTool() {
        String path = vtu2gltfPathField.getText();
        if (path.isEmpty()) {
            showAlert("Error", "Please specify the vtu2gltf tool path");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(path, "--help");
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                int exitCode = process.waitFor();
                Platform.runLater(() -> {
                    if (exitCode == 0 || output.toString().contains("vtu2gltf")) {
                        log("VTU2GLTF tool verified successfully");
                        showAlert("Success", "VTU2GLTF tool is working correctly");
                    } else {
                        showAlert("Error", "Failed to verify VTU2GLTF tool");
                    }
                });
                
            } catch (Exception e) {
                Platform.runLater(() -> {
                    log("Error testing vtu2gltf: " + e.getMessage());
                    showAlert("Error", "Failed to run vtu2gltf: " + e.getMessage());
                });
            }
        });
    }
    
    private void scanForVtuFiles() {
        String dirPath = outputDirField.getText();
        if (dirPath.isEmpty()) {
            showAlert("Error", "Please specify an output directory");
            return;
        }
        
        currentOutputDirectory = Paths.get(dirPath);
        if (!Files.exists(currentOutputDirectory)) {
            showAlert("Error", "Directory does not exist: " + dirPath);
            return;
        }
        
        // Clear existing groups
        fileGroups.clear();
        
        // Scan for VTU files
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, VtuFileGroup> detectedGroups = new HashMap<>();
                
                Files.walk(currentOutputDirectory)
                    .filter(path -> path.toString().toLowerCase().endsWith(".vtu"))
                    .forEach(path -> {
                        String filename = path.getFileName().toString();
                        String groupKey = detectGroupPattern(filename);
                        
                        VtuFileGroup group = detectedGroups.computeIfAbsent(groupKey, 
                            k -> new VtuFileGroup(k, "*" + k + "*.vtu"));
                        
                        group.addFile(new VtuFileGroup.VtuFile(
                            filename, 
                            path.toAbsolutePath().toString()
                        ));
                    });
                
                // Sort files in each group
                detectedGroups.values().forEach(VtuFileGroup::sortFilesByTimeStep);
                
                Platform.runLater(() -> {
                    fileGroups.addAll(detectedGroups.values());
                    log("Found " + fileGroups.size() + " VTU file groups");
                    statusLabel.setText("Found " + fileGroups.size() + " groups");
                });
                
            } catch (IOException e) {
                Platform.runLater(() -> {
                    log("Error scanning directory: " + e.getMessage());
                    showAlert("Error", "Failed to scan directory: " + e.getMessage());
                });
            }
        });
    }
    
    private String detectGroupPattern(String filename) {
        // Remove time step suffix if present
        String pattern = filename.replaceAll("_?t\\d+\\.vtu$", "");
        
        // Try to extract meaningful group name
        if (pattern.contains("_")) {
            String[] parts = pattern.split("_");
            if (parts.length > 1) {
                // Return the most significant part (e.g., "PAN", "COIL")
                return parts[parts.length > 2 ? 1 : 0];
            }
        }
        
        // Remove .vtu extension if still present
        return pattern.replace(".vtu", "");
    }
    
    private void addNewGroup() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        Label title = new Label("Add File Group");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
        Label prompt = new Label("Enter file pattern for the new group");
        TextField patternField = new TextField("*sol_*.vtu");
        HBox buttons = new HBox(10);
        Button ok = new Button("Add");
        Button cancel = new Button("Cancel");
        buttons.getChildren().addAll(ok, cancel);
        VBox content = new VBox(10, title, prompt, patternField, buttons);
        content.setPadding(new Insets(16));
        
        Stage owner = (Stage) root.getScene().getWindow();
        Stage popup = new Stage();
        popup.initOwner(owner);
        popup.setScene(new Scene(content));
        ok.setOnAction(e -> {
            String pattern = patternField.getText();
            if (pattern != null && !pattern.trim().isEmpty()) {
                String groupName = pattern.replace("*", "").replace(".vtu", "");
                VtuFileGroup newGroup = new VtuFileGroup(groupName, pattern);
                if (currentOutputDirectory != null) {
                    rescanGroup(newGroup);
                }
                fileGroups.add(newGroup);
            }
            popup.close();
        });
        cancel.setOnAction(e -> popup.close());
        
        try {
            WebAPI webAPI = WebAPI.getWebAPI(owner);
            if (webAPI != null) {
                webAPI.openStageAsPopup(popup);
            } else {
                popup.show();
            }
        } catch (Throwable t) {
            popup.show();
        }
    }
    
    private void rescanGroup(VtuFileGroup group) {
        if (currentOutputDirectory == null) return;
        
        group.clearFiles();
        String pattern = group.getPattern().replace("*", ".*");
        
        try {
            Files.walk(currentOutputDirectory)
                .filter(path -> path.toString().toLowerCase().endsWith(".vtu"))
                .filter(path -> path.getFileName().toString().matches(pattern))
                .forEach(path -> {
                    group.addFile(new VtuFileGroup.VtuFile(
                        path.getFileName().toString(),
                        path.toAbsolutePath().toString()
                    ));
                });
            
            group.sortFilesByTimeStep();
            
        } catch (IOException e) {
            logger.error("Error rescanning group", e);
        }
    }
    
    private void editSelectedGroup() {
        VtuFileGroup selected = groupsTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            EditGroupDialog dialog = new EditGroupDialog(selected);
            Node dlgContent = dialog.getDialogPane().getContent();
            VBox container = new VBox(10, dlgContent);
            container.setPadding(new Insets(16));
            HBox buttons = new HBox(10);
            Button save = new Button("Save");
            Button cancel = new Button("Cancel");
            buttons.getChildren().addAll(save, cancel);
            container.getChildren().add(buttons);
            
            Stage owner = (Stage) root.getScene().getWindow();
            Stage popup = new Stage();
            popup.initOwner(owner);
            popup.setScene(new Scene(container));
            save.setOnAction(e -> {
                ButtonType ok = ButtonType.OK;
                dialog.getResultConverter().call(ok);
                rescanGroup(selected);
                groupsTable.refresh();
                log("Updated group: " + selected.getGroupName());
                popup.close();
            });
            cancel.setOnAction(e -> popup.close());
            
            try {
                WebAPI webAPI = WebAPI.getWebAPI(owner);
                if (webAPI != null) {
                    webAPI.openStageAsPopup(popup);
                } else {
                    popup.show();
                }
            } catch (Throwable t) {
                popup.show();
            }
        }
    }
    
    private void removeSelectedGroup() {
        VtuFileGroup selected = groupsTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            fileGroups.remove(selected);
        }
    }
    
    private void configureSelectedGroup() {
        VtuFileGroup selected = groupsTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            configureGroup(selected);
        }
    }
    
    private void configureGroup(VtuFileGroup group) {
        VtuConversionDialog dialog = new VtuConversionDialog(group);
        Node dlgContent = dialog.getDialogPane().getContent();
        VBox container = new VBox(10, dlgContent);
        container.setPadding(new Insets(16));
        HBox buttons = new HBox(10);
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
        buttons.getChildren().addAll(save, cancel);
        container.getChildren().add(buttons);
        
        Stage owner = (Stage) root.getScene().getWindow();
        Stage popup = new Stage();
        popup.initOwner(owner);
        popup.setScene(new Scene(container));
        save.setOnAction(e -> {
            ButtonType ok = ButtonType.OK;
            dialog.getResultConverter().call(ok);
            groupsTable.refresh();
            log("Updated conversion options for group: " + group.getGroupName());
            popup.close();
        });
        cancel.setOnAction(e -> popup.close());
        
        try {
            WebAPI webAPI = WebAPI.getWebAPI(owner);
            if (webAPI != null) {
                webAPI.openStageAsPopup(popup);
            } else {
                popup.show();
            }
        } catch (Throwable t) {
            popup.show();
        }
    }
    
    private void convertSelectedGroups() {
        // Convert only groups that have checkbox selected
        List<VtuFileGroup> toConvert = fileGroups.stream()
            .filter(VtuFileGroup::isSelected)
            .filter(g -> g.getFileCount() > 0)
            .collect(Collectors.toList());
        
        if (toConvert.isEmpty()) {
            showAlert("Info", "No groups selected for conversion. Please check the boxes for groups you want to convert.");
            return;
        }
        
        performConversion(toConvert);
    }
    
    private void convertAllGroups() {
        List<VtuFileGroup> toConvert = fileGroups.stream()
            .filter(g -> g.getFileCount() > 0)
            .collect(Collectors.toList());
        
        if (toConvert.isEmpty()) {
            showAlert("Info", "No groups to convert");
            return;
        }
        
        // Select all groups for conversion
        toConvert.forEach(g -> g.setSelected(true));
        groupsTable.refresh();
        
        performConversion(toConvert);
    }
    
    private void performConversion(List<VtuFileGroup> groups) {
        if (vtu2gltfExecutable.isEmpty()) {
            showAlert("Error", "Please specify the vtu2gltf tool path");
            return;
        }
        if (currentOutputDirectory == null || !Files.exists(currentOutputDirectory)) {
            showAlert("Error", "Please set a valid output directory before converting");
            return;
        }
        java.io.File exe = new java.io.File(vtu2gltfExecutable);
        if (!exe.exists() || !exe.canExecute()) {
            showAlert("Error", "vtu2gltf executable not found or not executable: " + vtu2gltfExecutable);
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            // Count total files to process
            int totalFiles = groups.stream()
                .mapToInt(VtuFileGroup::getFileCount)
                .sum();
            int processedFiles = 0;
            
            for (VtuFileGroup group : groups) {
                Platform.runLater(() -> {
                    log("Processing group: " + group.getGroupName() + 
                        " (" + group.getFileCount() + " files)");
                });
                
                // Process each file in the group individually
                for (VtuFileGroup.VtuFile vtuFile : group.getFiles()) {
                    processedFiles++;
                    final int currentFile = processedFiles;
                    
                    long now = System.currentTimeMillis();
                    if (now - lastStatusUiUpdateMs >= 100) { // throttle
                        lastStatusUiUpdateMs = now;
                        Platform.runLater(() -> {
                            statusLabel.setText(String.format("Converting %s - %s (%d/%d)", 
                                group.getGroupName(), 
                                vtuFile.getFilename(),
                                currentFile, 
                                totalFiles));
                            conversionProgress.setProgress((double) currentFile / totalFiles);
                        });
                    }
                    
                    // Generate output filename for this specific file
                    String outputFilename = generateOutputFilename(group, vtuFile);
                    
                    // Build command for this single file
                    List<String> command = group.buildConversionCommand(
                        vtu2gltfExecutable, 
                        vtuFile,
                        outputFilename
                    );
                    
                    log("Converting: " + vtuFile.getFilename() + " -> " + outputFilename);
                    
                    try {
                        ProcessBuilder pb = new ProcessBuilder(command);
                        if (currentOutputDirectory != null) {
                            pb.directory(currentOutputDirectory.toFile());
                        }
                        
                        Process process = pb.start();
                        
                        // Read output (capture both stdout and stderr) concurrently
                        BufferedReader stdoutReader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()));
                        BufferedReader stderrReader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream()));
                        Thread outThread = new Thread(() -> {
                            try {
                                String line;
                                while ((line = stdoutReader.readLine()) != null) {
                                    final String output = line;
                                    log("  " + output);
                                }
                            } catch (IOException ignored) {}
                        }, "vtu-convert-stdout");
                        Thread errThread = new Thread(() -> {
                            try {
                                String line;
                                while ((line = stderrReader.readLine()) != null) {
                                    final String error = line;
                                    log("  ERROR: " + error);
                                }
                            } catch (IOException ignored) {}
                        }, "vtu-convert-stderr");
                        outThread.setDaemon(true);
                        errThread.setDaemon(true);
                        outThread.start();
                        errThread.start();
                        
                        int exitCode = process.waitFor();
                        try { outThread.join(500); } catch (InterruptedException ignored) {}
                        try { errThread.join(500); } catch (InterruptedException ignored) {}
                        if (exitCode == 0) {
                            log("  ✓ Successfully converted: " + outputFilename);
                        } else {
                            log("  ✗ Failed to convert " + vtuFile.getFilename() + 
                                " (exit code: " + exitCode + ")");
                        }
                        
                    } catch (Exception e) {
                        log("  ✗ Error converting " + vtuFile.getFilename() + ": " + e.getMessage());
                    }
                }
            }
            
            Platform.runLater(() -> {
                statusLabel.setText("Conversion complete");
                conversionProgress.setProgress(1.0);
                log("All conversions finished. Total files processed: " + totalFiles);
            });
        });
    }
    
    private String generateOutputFilename(VtuFileGroup group, VtuFileGroup.VtuFile vtuFile) {
        String baseExport = group.getConversionOption("export");
        if (baseExport == null || baseExport.isEmpty()) {
            baseExport = "output.gltf";
        }
        
        // Extract base name and extension
        String extension = ".gltf";
        if (baseExport.endsWith(".glb")) {
            extension = ".glb";
        }
        
        String filename = vtuFile.getFilename();
        String nameWithoutExt = filename.substring(0, filename.lastIndexOf('.'));
        
        // Create unique output name: group_filename.gltf
        // If it's a time series, include time step in the name
        if (vtuFile.hasTimeStep()) {
            return String.format("%s_%s_t%05d%s", 
                group.getGroupName(), 
                nameWithoutExt.replaceAll("_?t\\d+$", ""), // Remove time step from original name
                vtuFile.getTimeStep(),
                extension);
        } else {
            return String.format("%s_%s%s", 
                group.getGroupName(), 
                nameWithoutExt,
                extension);
        }
    }
    
    private void startWatching() {
        if (currentOutputDirectory == null || isWatching) return;
        
        try {
            watchService = FileSystems.getDefault().newWatchService();
            currentOutputDirectory.register(watchService, 
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
            
            isWatching = true;
            
            watchThread = new Thread(() -> {
                while (isWatching) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path path = (Path) event.context();
                            if (path.toString().toLowerCase().endsWith(".vtu")) {
                                Platform.runLater(() -> {
                                    log("New VTU file detected: " + path);
                                    // Rescan groups
                                    fileGroups.forEach(this::rescanGroup);
                                    groupsTable.refresh();
                                });
                            }
                        }
                        key.reset();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            watchThread.setDaemon(true);
            watchThread.start();
            
            log("Started monitoring directory for new VTU files");
            
        } catch (IOException e) {
            logger.error("Error starting directory watch", e);
        }
    }
    
    private void stopWatching() {
        isWatching = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.error("Error closing watch service", e);
            }
        }
        log("Stopped monitoring directory");
    }
    
    private void log(String message) {
        pendingConversionLogs.add("[" + java.time.LocalTime.now() + "] " + message + "\n");
        scheduleConversionLogFlush();
    }
    
    private void scheduleConversionLogFlush() {
        if (conversionFlushScheduled) return;
        conversionFlushScheduled = true;
        uiBatchExecutor.schedule(() -> Platform.runLater(() -> {
            try {
                StringBuilder batch = new StringBuilder();
                String line;
                int appended = 0;
                while ((line = pendingConversionLogs.poll()) != null && appended < 1000) {
                    batch.append(line);
                    appended++;
                }
                if (batch.length() > 0 && conversionLogArea != null) {
                    conversionLogArea.appendText(batch.toString());
                    trimTextAreaToLastLines(conversionLogArea, 100);
                }
            } finally {
                conversionFlushScheduled = false;
                if (!pendingConversionLogs.isEmpty()) {
                    scheduleConversionLogFlush();
                }
            }
        }), 100, TimeUnit.MILLISECONDS);
    }
    
    private void showAlert(String title, String content) {
        Platform.runLater(() -> {
            VBox card = new VBox(12);
            card.setPadding(new Insets(18));
            card.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 24,0,0,8);");
            card.setMaxWidth(640);
            Label titleLabel = new Label(title);
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");
            Label contentLabel = new Label(content);
            contentLabel.setWrapText(true);
            Button closeBtn = new Button("Close");
            HBox actions = new HBox(10, closeBtn);
            actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            card.getChildren().addAll(titleLabel, contentLabel, actions);
            
            // Overlay onto current Scene root StackPane
            Scene scene = root.getScene();
            if (scene != null && scene.getRoot() instanceof StackPane) {
                StackPane appRoot = (StackPane) scene.getRoot();
                StackPane overlay = new StackPane();
                overlay.setPickOnBounds(true);
                overlay.setStyle("-fx-background-color: rgba(0,0,0,0.45);");
                StackPane.setAlignment(card, javafx.geometry.Pos.CENTER);
                overlay.getChildren().add(card);
                appRoot.getChildren().add(overlay);
                closeBtn.setOnAction(e -> appRoot.getChildren().remove(overlay));
                // Consume clicks on content
                card.setOnMouseClicked(e -> e.consume());
                overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) { e.consume(); appRoot.getChildren().remove(overlay); } });
                overlay.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) appRoot.getChildren().remove(overlay); });
                overlay.requestFocus();
            } else {
                Alert alert = new Alert(
                    title.equals("Error") ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION
                );
                alert.setTitle(title);
                alert.setContentText(content);
                alert.show();
            }
        });
    }

    private void trimTextAreaToLastLines(TextArea area, int maxLines) {
        String text = area.getText();
        int lines = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                lines++;
                if (lines > maxLines) {
                    area.deleteText(0, i);
                    break;
                }
            }
        }
    }
    
    public void setOutputDirectory(String directory) {
        outputDirField.setText(directory);
        currentOutputDirectory = Paths.get(directory);
    }
    
    public void setVtu2gltfPath(String path) {
        vtu2gltfPathField.setText(path);
        vtu2gltfExecutable = path;
    }

    // Provide GLTF group patterns based on current VTU groups and conversion config
    public java.util.List<String> getGltfGroupPatterns() {
        return fileGroups.stream()
                .map(VtuFileGroup::getPattern)
                .collect(Collectors.toList());
    }
}