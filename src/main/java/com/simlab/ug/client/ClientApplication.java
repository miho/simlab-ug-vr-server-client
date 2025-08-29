package com.simlab.ug.client;

import com.simlab.ug.grpc.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.Node;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jpro.webapi.WebAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ClientApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(ClientApplication.class);
    
    private SimulationClient client;
    private TextField serverHostField;
    private TextField serverPortField;
    private Button connectButton;
    private Label connectionStatusLabel;
    
    private TextField scriptPathField;
    private TextField ugExecutableField;
    private TextField outputDirField;
    private VBox parametersContainer;
    private Map<String, Control> parameterControls = new HashMap<>();
    private Map<String, CheckBox> parameterEnabledCheckboxes = new HashMap<>();
    private Map<String, ParameterType> parameterTypes = new HashMap<>();
    
    private Button analyzeButton;
    private Button runButton;
    private Button stopButton;
    private ProgressBar progressBar;
    private Label progressLabel;
    private TextArea logArea;
    private ListView<String> resultsListView;
    private ResultsTabController resultsController;
    private FileSyncManager fileSyncManager;
    private LocalGltfServerManager localGltfServerManager;
    
    private String currentSimulationId;
    
    // Throttling/batching for logs and progress updates (helps JPro performance)
    private final ConcurrentLinkedQueue<String> pendingLogLines = new ConcurrentLinkedQueue<>();
    private volatile boolean logFlushScheduled = false;
    private static final ScheduledExecutorService uiBatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-batch-client");
        t.setDaemon(true);
        return t;
    });
    private long lastProgressUiUpdateMs = 0L;
    private Stage primaryStage;
    private StackPane appRoot;
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("UG4 Simulation Client");
        this.primaryStage = primaryStage;
        
        TabPane tabPane = new TabPane();
        
        Tab connectionTab = createConnectionTab();
        Tab simulationTab = createSimulationTab();
        Tab resultsTab = createResultsTab();
        Tab gltfServerTab = createGltfServerTab();
        
        tabPane.getTabs().addAll(connectionTab, simulationTab, resultsTab, gltfServerTab);
        
        appRoot = new StackPane(tabPane);
        Scene scene = new Scene(appRoot, 900, 700);
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Shutdown hook
        primaryStage.setOnCloseRequest(event -> {
            if (client != null) {
                client.shutdown();
            }
            if (localGltfServerManager != null) {
                localGltfServerManager.stop();
            }
            Platform.exit();
        });
    }
    
    private Tab createConnectionTab() {
        Tab tab = new Tab("Connection");
        tab.setClosable(false);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        
        Label hostLabel = new Label("Server Host:");
        serverHostField = new TextField("localhost");
        
        Label portLabel = new Label("Server Port:");
        serverPortField = new TextField("50051");
        
        connectButton = new Button("Connect");
        connectButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        connectButton.setOnAction(e -> connectToServer());
        
        connectionStatusLabel = new Label("Status: Disconnected");
        connectionStatusLabel.setTextFill(Color.RED);
        
        grid.add(hostLabel, 0, 0);
        grid.add(serverHostField, 1, 0);
        grid.add(portLabel, 0, 1);
        grid.add(serverPortField, 1, 1);
        grid.add(connectButton, 0, 2);
        grid.add(connectionStatusLabel, 1, 2);
        
        // Server info section
        TitledPane serverInfoPane = new TitledPane("Server Information", new Label("Not connected"));
        
        content.getChildren().addAll(grid, serverInfoPane);
        tab.setContent(content);
        
        return tab;
    }
    
    private Tab createSimulationTab() {
        Tab tab = new Tab("Simulation");
        tab.setClosable(false);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        // Script selection
        GridPane scriptGrid = new GridPane();
        scriptGrid.setHgap(10);
        scriptGrid.setVgap(10);
        
        Label scriptLabel = new Label("Lua Script:");
        scriptPathField = new TextField();
        scriptPathField.setPrefWidth(400);
        Button browseScriptButton = new Button("Browse...");
        browseScriptButton.setOnAction(e -> browseForScript());
        
        Label ugLabel = new Label("UG4 Executable:");
        ugExecutableField = new TextField();
        ugExecutableField.setPrefWidth(400);
        Button browseUgButton = new Button("Browse...");
        browseUgButton.setOnAction(e -> browseForUgExecutable());
        
        Label outputLabel = new Label("Output Directory:");
        outputDirField = new TextField();
        outputDirField.setPrefWidth(400);
        Button browseOutputButton = new Button("Browse...");
        browseOutputButton.setOnAction(e -> browseForOutputDirectory());
        
        analyzeButton = new Button("Analyze Script");
        analyzeButton.setOnAction(e -> analyzeScript());
        
        scriptGrid.add(scriptLabel, 0, 0);
        scriptGrid.add(scriptPathField, 1, 0);
        scriptGrid.add(browseScriptButton, 2, 0);
        
        scriptGrid.add(ugLabel, 0, 1);
        scriptGrid.add(ugExecutableField, 1, 1);
        scriptGrid.add(browseUgButton, 2, 1);
        
        scriptGrid.add(outputLabel, 0, 2);
        scriptGrid.add(outputDirField, 1, 2);
        scriptGrid.add(browseOutputButton, 2, 2);
        
        scriptGrid.add(analyzeButton, 1, 3);
        
        // Parameters section
        TitledPane parametersPane = new TitledPane("Script Parameters", createParametersSection());
        
        // Control buttons
        HBox controlBox = new HBox(10);
        runButton = new Button("Run Simulation");
        runButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        runButton.setDisable(true);
        runButton.setOnAction(e -> runSimulation());
        
        stopButton = new Button("Stop Simulation");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopSimulation());
        
        controlBox.getChildren().addAll(runButton, stopButton);
        
        // Progress section
        VBox progressBox = new VBox(5);
        progressLabel = new Label("Ready");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBox.getChildren().addAll(progressLabel, progressBar);
        
        // Log section
        TitledPane logPane = new TitledPane("Simulation Log", createLogSection());
        
        content.getChildren().addAll(
                scriptGrid,
                parametersPane,
                controlBox,
                progressBox,
                logPane
        );
        
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        tab.setContent(scrollPane);
        
        return tab;
    }
    
    private VBox createParametersSection() {
        parametersContainer = new VBox(10);
        parametersContainer.setPadding(new Insets(10));
        
        Label infoLabel = new Label("Analyze a script to see available parameters");
        infoLabel.setStyle("-fx-font-style: italic;");
        parametersContainer.getChildren().add(infoLabel);
        
        return parametersContainer;
    }
    
    private VBox createLogSection() {
        VBox box = new VBox(5);
        
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(10);
        logArea.setWrapText(true);
        
        Button clearButton = new Button("Clear Log");
        clearButton.setOnAction(e -> logArea.clear());
        
        box.getChildren().addAll(logArea, clearButton);
        return box;
    }
    
    private Tab createResultsTab() {
        Tab tab = new Tab("Results");
        tab.setClosable(false);
        
        // Use the new enhanced results tab controller
        ResultsTabController resultsController = new ResultsTabController();
        VBox resultsContent = resultsController.createResultsTab();
        
        // Set initial output directory if available
        if (outputDirField != null && !outputDirField.getText().isEmpty()) {
            resultsController.setOutputDirectory(outputDirField.getText());
        }
        
        // Store reference for later use
        this.resultsController = resultsController;
        // Initialize local managers
        this.fileSyncManager = null;
        
        tab.setContent(resultsContent);
        
        return tab;
    }

    private Tab createGltfServerTab() {
        Tab tab = new Tab("GLTF Server");
        tab.setClosable(false);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        Label info = new Label("Serve GLTF files locally from the client's output directory.");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        Label portLabel = new Label("Port:");
        TextField portField = new TextField("50056");
        portField.setPrefWidth(120);

        Label rootLabel = new Label("Root Directory:");
        TextField rootField = new TextField();
        rootField.setPrefWidth(400);

        Button useResultsDirBtn = new Button("Use Results Directory");
        useResultsDirBtn.setOnAction(e -> {
            if (resultsController != null) {
                rootField.setText(resultsController != null ? getResultsOutputDir() : "");
            }
        });

        Button startBtn = new Button("Start");
        startBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        Button stopBtn = new Button("Stop");
        stopBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");
        stopBtn.setDisable(true);

        Button applyGroupsBtn = new Button("Sync Groups from Results");
        applyGroupsBtn.setOnAction(e -> {
            if (localGltfServerManager != null && resultsController != null) {
                localGltfServerManager.setGroupPatterns(resultsController.getGltfGroupPatterns());
            }
        });

        startBtn.setOnAction(e -> {
            try {
                int port = Integer.parseInt(portField.getText());
                String root = rootField.getText().isEmpty() ? getResultsOutputDir() : rootField.getText();
                if (localGltfServerManager != null) {
                    localGltfServerManager.stop();
                }
                localGltfServerManager = new LocalGltfServerManager(port, root);
                if (resultsController != null) {
                    localGltfServerManager.setGroupPatterns(resultsController.getGltfGroupPatterns());
                }
                localGltfServerManager.start();
                startBtn.setDisable(true);
                stopBtn.setDisable(false);
            } catch (Exception ex) {
                showAlert("GLTF Server Error", ex.getMessage());
            }
        });

        stopBtn.setOnAction(e -> {
            if (localGltfServerManager != null) {
                localGltfServerManager.stop();
                startBtn.setDisable(false);
                stopBtn.setDisable(true);
            }
        });

        grid.add(portLabel, 0, 0);
        grid.add(portField, 1, 0);
        grid.add(rootLabel, 0, 1);
        grid.add(rootField, 1, 1);
        grid.add(useResultsDirBtn, 2, 1);
        grid.add(startBtn, 0, 2);
        grid.add(stopBtn, 1, 2);
        grid.add(applyGroupsBtn, 2, 2);

        content.getChildren().addAll(info, grid);
        tab.setContent(content);
        return tab;
    }

    private String getResultsOutputDir() {
        try {
            java.lang.reflect.Field f = ResultsTabController.class.getDeclaredField("currentOutputDirectory");
            f.setAccessible(true);
            Object val = f.get(resultsController);
            if (val instanceof java.nio.file.Path) {
                return ((java.nio.file.Path) val).toString();
            }
        } catch (Exception ignored) {}
        return outputDirField != null ? outputDirField.getText() : "";
    }
    
    private void connectToServer() {
        // Check if we're currently connected (disconnect functionality)
        if (client != null && client.isConnected()) {
            // Disconnect
            client.shutdown();
            client = null;
            Platform.runLater(() -> {
                connectionStatusLabel.setText("Status: Disconnected");
                connectionStatusLabel.setTextFill(Color.RED);
                connectButton.setText("Connect");
                connectButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
                serverHostField.setDisable(false);
                serverPortField.setDisable(false);
                log("Disconnected from server");
            });
            return;
        }
        
        // Connect to server
        try {
            String host = serverHostField.getText();
            int port = Integer.parseInt(serverPortField.getText());
            
            if (client != null) {
                client.shutdown();
            }
            
            client = new SimulationClient(host, port);
            
            if (client.isConnected()) {
                ServerStatus status = client.getServerStatus();
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("Status: Connected");
                    connectionStatusLabel.setTextFill(Color.GREEN);
                    connectButton.setText("Disconnect");
                    connectButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");
                    serverHostField.setDisable(true);
                    serverPortField.setDisable(true);
                    ugExecutableField.setText(status.getUgPath());
                    log("Connected to server at " + host + ":" + port);
                });
            } else {
                throw new RuntimeException("Failed to connect");
            }
            
        } catch (Exception e) {
            logger.error("Failed to connect to server", e);
            showAlert("Connection Error", "Failed to connect to server: " + e.getMessage());
            Platform.runLater(() -> {
                connectionStatusLabel.setText("Status: Disconnected");
                connectionStatusLabel.setTextFill(Color.RED);
                connectButton.setText("Connect");
                connectButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
            });
        }
    }
    
    private void browseForScript() {
        WebAPI webAPI = WebAPI.getWebAPI(primaryStage);
        if (webAPI != null) {
            TextField input = new TextField(scriptPathField.getText());
            input.setPrefWidth(520);
            Button ok = new Button("OK");
            Button cancel = new Button("Cancel");
            HBox actions = new HBox(10, cancel, ok);
            actions.setAlignment(Pos.CENTER_RIGHT);
            VBox card = new VBox(12, new Label("Enter script path (server path)"), input, actions);
            card.setMaxWidth(640);
            Runnable close = openOverlay(card);
            ok.setOnAction(e -> { scriptPathField.setText(input.getText()); close.run(); });
            cancel.setOnAction(e -> close.run());
        } else {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select Lua Script");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Lua Scripts", "*.lua")
            );
            File file = fileChooser.showOpenDialog(scriptPathField.getScene().getWindow());
            if (file != null) {
                scriptPathField.setText(file.getAbsolutePath());
            }
        }
    }
    
    private void browseForUgExecutable() {
        WebAPI webAPI = WebAPI.getWebAPI(primaryStage);
        if (webAPI != null) {
            TextField input = new TextField(ugExecutableField.getText());
            input.setPrefWidth(520);
            Button ok = new Button("OK");
            Button cancel = new Button("Cancel");
            HBox actions = new HBox(10, cancel, ok);
            actions.setAlignment(Pos.CENTER_RIGHT);
            VBox card = new VBox(12, new Label("Enter UG4 executable path (server path)"), input, actions);
            card.setMaxWidth(640);
            Runnable close = openOverlay(card);
            ok.setOnAction(e -> { ugExecutableField.setText(input.getText()); close.run(); });
            cancel.setOnAction(e -> close.run());
        } else {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select UG4 Executable");
            File file = fileChooser.showOpenDialog(ugExecutableField.getScene().getWindow());
            if (file != null) {
                ugExecutableField.setText(file.getAbsolutePath());
            }
        }
    }
    
    private void browseForOutputDirectory() {
        WebAPI webAPI = WebAPI.getWebAPI(primaryStage);
        if (webAPI != null) {
            TextField input = new TextField(outputDirField.getText());
            input.setPrefWidth(520);
            Button ok = new Button("OK");
            Button cancel = new Button("Cancel");
            HBox actions = new HBox(10, cancel, ok);
            actions.setAlignment(Pos.CENTER_RIGHT);
            VBox card = new VBox(12, new Label("Enter output directory (server path)"), input, actions);
            card.setMaxWidth(640);
            Runnable close = openOverlay(card);
            ok.setOnAction(e -> { outputDirField.setText(input.getText()); close.run(); });
            cancel.setOnAction(e -> close.run());
        } else {
            DirectoryChooser dirChooser = new DirectoryChooser();
            dirChooser.setTitle("Select Output Directory");
            File dir = dirChooser.showDialog(outputDirField.getScene().getWindow());
            if (dir != null) {
                outputDirField.setText(dir.getAbsolutePath());
            }
        }
    }
    
    private void analyzeScript() {
        if (client == null || !client.isConnected()) {
            showAlert("Error", "Not connected to server");
            return;
        }
        
        String scriptPath = scriptPathField.getText();
        if (scriptPath.isEmpty()) {
            showAlert("Error", "Please select a script file");
            return;
        }
        
        ScriptAnalysis analysis = client.analyzeScript(scriptPath);
        
        if (analysis.getSuccess()) {
            Platform.runLater(() -> {
                parametersContainer.getChildren().clear();
                parameterControls.clear();
                parameterEnabledCheckboxes.clear();
                parameterTypes.clear();
                
                for (ScriptParameter param : analysis.getParametersList()) {
                    HBox paramBox = new HBox(10);
                    paramBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    
                    // Checkbox to enable/disable this parameter
                    CheckBox enableCheckbox = new CheckBox();
                    enableCheckbox.setSelected(true);
                    enableCheckbox.setTooltip(new Tooltip("Uncheck to omit this parameter"));
                    parameterEnabledCheckboxes.put(param.getName(), enableCheckbox);
                    
                    Label label = new Label(param.getName() + ":");
                    label.setPrefWidth(130);
                    
                    Control control = createParameterControl(param);
                    parameterControls.put(param.getName(), control);
                    parameterTypes.put(param.getName(), param.getType());
                    
                    // Bind control enable state to checkbox
                    control.disableProperty().bind(enableCheckbox.selectedProperty().not());
                    
                    Label descLabel = new Label(param.getDescription());
                    descLabel.setStyle("-fx-font-size: 10; -fx-text-fill: gray;");
                    descLabel.setPrefWidth(200);
                    descLabel.setWrapText(true);
                    
                    paramBox.getChildren().addAll(enableCheckbox, label, control, descLabel);
                    parametersContainer.getChildren().add(paramBox);
                }
                
                if (analysis.getParametersList().isEmpty()) {
                    Label noParamsLabel = new Label("No parameters found in script");
                    parametersContainer.getChildren().add(noParamsLabel);
                } else {
                    // Add control buttons for parameter selection
                    HBox controlBox = new HBox(10);
                    Button selectAllBtn = new Button("Select All");
                    selectAllBtn.setStyle("-fx-font-size: 11;");
                    selectAllBtn.setOnAction(e -> {
                        parameterEnabledCheckboxes.values().forEach(cb -> cb.setSelected(true));
                    });
                    
                    Button deselectAllBtn = new Button("Deselect All");
                    deselectAllBtn.setStyle("-fx-font-size: 11;");
                    deselectAllBtn.setOnAction(e -> {
                        parameterEnabledCheckboxes.values().forEach(cb -> cb.setSelected(false));
                    });
                    
                    Label helpLabel = new Label("Tip: Uncheck parameters to omit them from the command");
                    helpLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11; -fx-text-fill: #666;");
                    
                    controlBox.getChildren().addAll(selectAllBtn, deselectAllBtn, helpLabel);
                    parametersContainer.getChildren().add(0, controlBox);
                }
                
                runButton.setDisable(false);
                log("Script analysis complete. Found " + analysis.getParametersCount() + " parameters.");
            });
        } else {
            showAlert("Analysis Error", analysis.getErrorMessage());
        }
    }
    
    private Control createParameterControl(ScriptParameter param) {
        switch (param.getType()) {
            case STRING:
                // Use display value if available, otherwise use the parsed value
                String stringValue = !param.getDisplayValue().isEmpty() 
                    ? param.getDisplayValue() : param.getStringValue();
                TextField textField = new TextField(stringValue);
                textField.setPrefWidth(200);
                return textField;
                
            case INTEGER:
                // Use display value if available to preserve original format
                String intValue = !param.getDisplayValue().isEmpty()
                    ? param.getDisplayValue() : String.valueOf(param.getIntValue());
                TextField intField = new TextField(intValue);
                intField.setPrefWidth(100);
                intField.setPromptText("Integer value");
                // Add validation to ensure only integers are entered
                intField.textProperty().addListener((obs, oldVal, newVal) -> {
                    if (!newVal.matches("-?\\d*")) {
                        intField.setText(oldVal);
                    }
                });
                return intField;
                
            case FLOAT:
                // Use display value to preserve the exact notation from the script
                String floatValue = !param.getDisplayValue().isEmpty()
                    ? param.getDisplayValue() : String.valueOf(param.getFloatValue());
                TextField floatField = new TextField(floatValue);
                floatField.setPrefWidth(120);
                floatField.setPromptText("Numeric value");
                // Add validation for numeric input (including scientific notation)
                floatField.textProperty().addListener((obs, oldVal, newVal) -> {
                    if (!newVal.matches("-?\\d*\\.?\\d*([eE][+-]?\\d*)?")) {
                        floatField.setText(oldVal);
                    }
                });
                return floatField;
                
            case BOOLEAN:
                CheckBox checkBox = new CheckBox();
                checkBox.setSelected(param.getBoolValue());
                return checkBox;
                
            case ARRAY:
                TextField arrayField = new TextField(String.join(",", param.getArrayValue().getValuesList()));
                arrayField.setPrefWidth(200);
                return arrayField;
                
            default:
                return new TextField();
        }
    }
    
    private void runSimulation() {
        if (client == null || !client.isConnected()) {
            showAlert("Error", "Not connected to server");
            return;
        }
        
        currentSimulationId = UUID.randomUUID().toString();
        String scriptPath = scriptPathField.getText();
        String ugExecutable = ugExecutableField.getText();
        String outputDir = outputDirField.getText();
        
        if (scriptPath.isEmpty() || ugExecutable.isEmpty()) {
            showAlert("Error", "Please provide script path and UG executable");
            return;
        }
        
        List<ParameterValue> parameters = collectParameters();
        
        Platform.runLater(() -> {
            runButton.setDisable(true);
            stopButton.setDisable(false);
            progressBar.setProgress(0);
            progressLabel.setText("Starting simulation...");
            logArea.clear();
        });
        
        client.runSimulation(currentSimulationId, scriptPath, ugExecutable, parameters, outputDir,
                new SimulationClient.SimulationListener() {
                    @Override
                    public void onProgress(double percentage, String message, int current, int total) {
                        long now = System.currentTimeMillis();
                        if (now - lastProgressUiUpdateMs >= 100) { // max ~10 updates/sec
                            lastProgressUiUpdateMs = now;
                            Platform.runLater(() -> {
                                progressBar.setProgress(percentage / 100.0);
                                progressLabel.setText(message);
                            });
                        }
                    }
                    
                    @Override
                    public void onLog(LogLevel level, String message, long timestamp) {
                        log(message);
                    }
                    
                    @Override
                    public void onResult(SimulationResult result) {
                        Platform.runLater(() -> {
                            progressLabel.setText("Simulation " + result.getFinalState());
                            log("Simulation completed in " + result.getDurationMs() + " ms");
                            log("Output files: " + result.getOutputFilesList());
                            
                            // Update results controller with output directory
                            if (resultsController != null && !outputDirField.getText().isEmpty()) {
                                resultsController.setOutputDirectory(outputDirField.getText());
                            }
                            
                            // Keep old results list for backward compatibility
                            if (resultsListView != null) {
                                resultsListView.getItems().addAll(result.getOutputFilesList());
                            }
                        });
                    }
                    
                    @Override
                    public void onError(String error, String stackTrace) {
                        Platform.runLater(() -> {
                            log("ERROR: " + error);
                            if (!stackTrace.isEmpty()) {
                                log(stackTrace);
                            }
                            showAlert("Simulation Error", error);
                        });
                    }
                    
                    @Override
                    public void onComplete() {
                        Platform.runLater(() -> {
                            runButton.setDisable(false);
                            stopButton.setDisable(true);
                            progressBar.setProgress(1.0);
                        });
                    }
                });

        // Start VTU file sync to client output directory (initial + live updates)
        if (fileSyncManager == null && client != null) {
            fileSyncManager = new FileSyncManager(client);
        }
        if (fileSyncManager != null && outputDir != null && !outputDir.isEmpty()) {
            fileSyncManager.startSync(currentSimulationId, java.util.Arrays.asList("*.vtu"), true, outputDir);
        }
    }
    
    private List<ParameterValue> collectParameters() {
        List<ParameterValue> parameters = new ArrayList<>();
        
        for (Map.Entry<String, Control> entry : parameterControls.entrySet()) {
            String name = entry.getKey();
            Control control = entry.getValue();
            
            // Check if this parameter is enabled
            CheckBox enabledCheckbox = parameterEnabledCheckboxes.get(name);
            if (enabledCheckbox != null && !enabledCheckbox.isSelected()) {
                // Skip this parameter if it's not enabled
                continue;
            }
            
            ParameterValue.Builder param = ParameterValue.newBuilder().setName(name);
            
            // Get the parameter type we stored during analysis
            ParameterType type = parameterTypes.get(name);
            
            if (control instanceof CheckBox) {
                param.setBoolValue(((CheckBox) control).isSelected());
            } else if (control instanceof TextField) {
                String text = ((TextField) control).getText().trim();
                
                // Use the stored type to determine how to parse the value
                if (type == ParameterType.INTEGER) {
                    try {
                        param.setIntValue(Integer.parseInt(text));
                    } catch (NumberFormatException e) {
                        // Try parsing as double then converting
                        try {
                            double val = Double.parseDouble(text);
                            param.setIntValue((int) val);
                        } catch (NumberFormatException e2) {
                            param.setIntValue(0);
                        }
                    }
                } else if (type == ParameterType.FLOAT) {
                    try {
                        param.setFloatValue(Double.parseDouble(text));
                    } catch (NumberFormatException e) {
                        param.setFloatValue(0.0);
                    }
                } else {
                    // STRING or unknown
                    param.setStringValue(text);
                }
            } else if (control instanceof Spinner<?>) {
                // Legacy support for any remaining spinners
                Object value = ((Spinner<?>) control).getValue();
                if (value instanceof Integer) {
                    param.setIntValue((Integer) value);
                } else if (value instanceof Double) {
                    param.setFloatValue((Double) value);
                }
            }
            
            parameters.add(param.build());
        }
        
        return parameters;
    }
    
    private void stopSimulation() {
        if (client != null && currentSimulationId != null) {
            CompletableFuture
                .supplyAsync(() -> client.stopSimulation(currentSimulationId))
                .thenAccept(success -> Platform.runLater(() -> {
                    if (success) {
                        progressLabel.setText("Simulation stopped");
                        runButton.setDisable(false);
                        stopButton.setDisable(true);
                    } else {
                        showAlert("Error", "Failed to stop simulation");
                    }
                }));
        }
    }
    
    private void refreshResults() {
        // Implementation to refresh results from server
    }
    
    private void downloadSelectedResult() {
        String selected = resultsListView.getSelectionModel().getSelectedItem();
        if (selected != null && currentSimulationId != null && client != null) {
            // Extract just the filename from the full path
            String filename = new File(selected).getName();
            
            FileChooser fileChooser = new FileChooser();
            fileChooser.setInitialFileName(filename);
            File saveFile = fileChooser.showSaveDialog(resultsListView.getScene().getWindow());
            
            if (saveFile != null) {
                log("Downloading " + filename + " to " + saveFile.getAbsolutePath());
                
                // Use the filename pattern to match the file
                List<String> patterns = Arrays.asList(filename);
                
                // Create a list to collect file data
                List<FileData> receivedFiles = new ArrayList<>();
                
                client.getSimulationResults(currentSimulationId, patterns,
                        fileData -> {
                            // Collect the file data
                            receivedFiles.add(fileData);
                            Platform.runLater(() -> 
                                    log("Received: " + fileData.getFilename() + " (" + 
                                        fileData.getContent().size() + " bytes)"));
                        },
                        () -> {
                            // Write the first matching file when complete
                            if (!receivedFiles.isEmpty()) {
                                try {
                                    FileData fileData = receivedFiles.get(0);
                                    try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                                        fos.write(fileData.getContent().toByteArray());
                                        Platform.runLater(() -> 
                                                log("Successfully saved: " + saveFile.getAbsolutePath()));
                                    }
                                } catch (IOException e) {
                                    logger.error("Error saving file", e);
                                    Platform.runLater(() -> 
                                            showAlert("Download Error", "Failed to save file: " + e.getMessage()));
                                }
                            } else {
                                Platform.runLater(() -> 
                                        log("No files received for download"));
                            }
                        }
                );
            }
        } else {
            showAlert("Download Error", "Please select a file and ensure a simulation has been run");
        }
    }
    
    private void log(String message) {
        pendingLogLines.add("[" + java.time.LocalTime.now() + "] " + message + "\n");
        scheduleLogFlush();
    }
    
    private void scheduleLogFlush() {
        if (logFlushScheduled) return;
        logFlushScheduled = true;
        uiBatchExecutor.schedule(() -> Platform.runLater(() -> {
            try {
                StringBuilder batch = new StringBuilder();
                String line;
                int appended = 0;
                while ((line = pendingLogLines.poll()) != null && appended < 1000) {
                    batch.append(line);
                    appended++;
                }
                if (batch.length() > 0 && logArea != null) {
                    logArea.appendText(batch.toString());
                    trimTextAreaToLastLines(logArea, 100);
                }
            } finally {
                logFlushScheduled = false;
                if (!pendingLogLines.isEmpty()) {
                    scheduleLogFlush();
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
            actions.setAlignment(Pos.CENTER_RIGHT);
            card.getChildren().addAll(titleLabel, contentLabel, actions);
            Runnable close = openOverlay(card);
            closeBtn.setOnAction(e -> close.run());
        });
    }

    private Runnable openOverlay(Node content) {
        if (appRoot == null) {
            return () -> {};
        }
        StackPane overlay = new StackPane();
        overlay.setPickOnBounds(true);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.45);");
        StackPane.setAlignment(content, Pos.CENTER);
        content.setOnMouseClicked(ev -> ev.consume());
        overlay.getChildren().add(content);
        appRoot.getChildren().add(overlay);
        Runnable close = () -> appRoot.getChildren().remove(overlay);
        overlay.setOnMouseClicked(e -> {
            if (e.getTarget() == overlay) {
                e.consume();
                close.run();
            }
        });
        overlay.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) close.run(); });
        overlay.requestFocus();
        return close;
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
    
    public static void main(String[] args) {
        launch(args);
    }
}