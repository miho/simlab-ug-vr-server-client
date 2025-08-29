package com.simlab.ug.server;

import com.simlab.ug.common.SimulationExecutor;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.jpro.webapi.WebAPI;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ServerApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(ServerApplication.class);
    
    private Server grpcServer;
    private SimulationServiceImpl simulationService;
    private ResultsServiceImpl resultsService;
    private TextField portField;
    private TextField ugPathField;
    private TextField workingDirField;
    private Button startButton;
    private Button stopButton;
    private TextArea logArea;
    private Label statusLabel;
    private ListView<String> activeSimulationsList;
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("UG4 Simulation Server");
        
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        
        // Server Configuration Section
        TitledPane configPane = createConfigurationPane();
        
        // Server Control Section
        HBox controlBox = createControlBox();
        
        // Status Section
        statusLabel = new Label("Server Status: Stopped");
        statusLabel.setStyle("-fx-font-weight: bold");
        
        // Active Simulations Section
        TitledPane simulationsPane = createSimulationsPane();
        
        // Log Section
        TitledPane logPane = createLogPane();
        
        root.getChildren().addAll(
                configPane,
                controlBox,
                statusLabel,
                simulationsPane,
                logPane
        );
        
        Scene scene = new Scene(root, 800, 600);
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Shutdown hook
        primaryStage.setOnCloseRequest(event -> {
            stopServer();
            Platform.exit();
        });
    }
    
    private TitledPane createConfigurationPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        
        // Port configuration
        Label portLabel = new Label("Server Port:");
        portField = new TextField("50051");
        portField.setPrefWidth(100);
        
        // UG4 Path configuration
        Label ugLabel = new Label("UG4 Executable:");
        ugPathField = new TextField("C:\\Dev\\repos\\ug4\\bin\\ugshell.exe");
        ugPathField.setPrefWidth(300);
        Button browseUgButton = new Button("Browse...");
        browseUgButton.setOnAction(e -> browseForUgExecutable());
        
        // Working Directory configuration
        Label workDirLabel = new Label("Working Directory:");
        workingDirField = new TextField(System.getProperty("user.dir"));
        workingDirField.setPrefWidth(300);
        Button browseWorkDirButton = new Button("Browse...");
        browseWorkDirButton.setOnAction(e -> browseForWorkingDirectory());
        
        grid.add(portLabel, 0, 0);
        grid.add(portField, 1, 0);
        
        grid.add(ugLabel, 0, 1);
        grid.add(ugPathField, 1, 1);
        grid.add(browseUgButton, 2, 1);
        
        grid.add(workDirLabel, 0, 2);
        grid.add(workingDirField, 1, 2);
        grid.add(browseWorkDirButton, 2, 2);
        
        TitledPane pane = new TitledPane("Server Configuration", grid);
        pane.setCollapsible(false);
        return pane;
    }
    
    private HBox createControlBox() {
        HBox box = new HBox(10);
        box.setPadding(new Insets(10));
        
        startButton = new Button("Start Server");
        startButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        startButton.setOnAction(e -> startServer());
        
        stopButton = new Button("Stop Server");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopServer());
        
        box.getChildren().addAll(startButton, stopButton);
        return box;
    }
    
    private TitledPane createSimulationsPane() {
        VBox content = new VBox(5);
        
        activeSimulationsList = new ListView<>();
        activeSimulationsList.setPrefHeight(150);
        
        content.getChildren().add(activeSimulationsList);
        
        TitledPane pane = new TitledPane("Active Simulations", content);
        return pane;
    }
    
    private TitledPane createLogPane() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(10);
        logArea.setWrapText(true);
        
        Button clearButton = new Button("Clear Log");
        clearButton.setOnAction(e -> logArea.clear());
        
        VBox content = new VBox(5);
        content.getChildren().addAll(logArea, clearButton);
        
        TitledPane pane = new TitledPane("Server Log", content);
        return pane;
    }
    
    private void browseForUgExecutable() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select UG4 Executable");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Executable Files", "*.exe", "*.sh", "*"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        
        File file = fileChooser.showOpenDialog(ugPathField.getScene().getWindow());
        if (file != null) {
            ugPathField.setText(file.getAbsolutePath());
        }
    }
    
    private void browseForWorkingDirectory() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Working Directory");
        dirChooser.setInitialDirectory(new File(workingDirField.getText()));
        
        File dir = dirChooser.showDialog(workingDirField.getScene().getWindow());
        if (dir != null) {
            workingDirField.setText(dir.getAbsolutePath());
        }
    }
    
    private void startServer() {
        try {
            int port = Integer.parseInt(portField.getText());
            String ugPath = ugPathField.getText();
            
            if (ugPath.isEmpty()) {
                showAlert("Error", "Please specify the UG4 executable path");
                return;
            }
            
            simulationService = new SimulationServiceImpl();
            simulationService.setUgPath(ugPath);
            simulationService.setInitialWorkingDirectory(workingDirField.getText());
            resultsService = new ResultsServiceImpl(workingDirField.getText());
            simulationService.setResultsService(resultsService);
            
            grpcServer = ServerBuilder.forPort(port)
                    .addService(simulationService)
//                    .addService(resultsService)
                    .build()
                    .start();
            
            log("Server started on port " + port);
            log("UG4 executable: " + ugPath);
            log("Working directory: " + workingDirField.getText());
            
            Platform.runLater(() -> {
                statusLabel.setText("Server Status: Running on port " + port);
                statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: green;");
                startButton.setDisable(true);
                stopButton.setDisable(false);
                portField.setDisable(true);
                ugPathField.setDisable(true);
                workingDirField.setDisable(true);
            });
            
            // Start monitoring thread
            startMonitoring();
            
        } catch (IOException e) {
            logger.error("Failed to start server", e);
            showAlert("Error", "Failed to start server: " + e.getMessage());
        } catch (NumberFormatException e) {
            showAlert("Error", "Invalid port number");
        }
    }
    
    private void stopServer() {
        if (grpcServer != null) {
            try {
                grpcServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                log("Server stopped");
                
                Platform.runLater(() -> {
                    statusLabel.setText("Server Status: Stopped");
                    statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: red;");
                    startButton.setDisable(false);
                    stopButton.setDisable(true);
                    portField.setDisable(false);
                    ugPathField.setDisable(false);
                    workingDirField.setDisable(false);
                    activeSimulationsList.getItems().clear();
                });
                
            } catch (InterruptedException e) {
                logger.error("Error stopping server", e);
            }
        }
    }
    
    private void startMonitoring() {
        Thread monitorThread = new Thread(() -> {
            while (grpcServer != null && !grpcServer.isShutdown()) {
                try {
                    Thread.sleep(2000);
                    // Update active simulations list
                    if (simulationService != null) {
                        Platform.runLater(() -> {
                            activeSimulationsList.getItems().clear();
                            Map<String, SimulationExecutor> simulations = simulationService.getActiveSimulations();
                            for (Map.Entry<String, SimulationExecutor> entry : simulations.entrySet()) {
                                String simId = entry.getKey();
                                SimulationExecutor exec = entry.getValue();
                                String display = String.format("%s - %s (%.1f%%)", 
                                    simId.substring(0, 8), 
                                    exec.getState().toString(),
                                    exec.getProgress() * 100);
                                activeSimulationsList.getItems().add(display);
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
    
    private void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText("[" + java.time.LocalTime.now() + "] " + message + "\n");
        });
    }
    
    private void showAlert(String title, String content) {
        Platform.runLater(() -> {
            try {
                Stage owner = (Stage) statusLabel.getScene().getWindow();
                WebAPI webAPI = WebAPI.getWebAPI(owner);
                VBox box = new VBox(10);
                box.setPadding(new Insets(16));
                Label titleLabel = new Label(title);
                titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
                Label contentLabel = new Label(content);
                contentLabel.setWrapText(true);
                Button closeBtn = new Button("Close");

                Stage popup = new Stage();
                popup.initOwner(owner);
                VBox root = new VBox(10, box, new HBox(10, closeBtn));
                root.setPadding(new Insets(16));
                box.getChildren().addAll(titleLabel, contentLabel);
                popup.setScene(new Scene(root));
                closeBtn.setOnAction(e -> popup.close());

                if (webAPI != null) {
                    webAPI.openStageAsPopup(popup);
                } else {
                    popup.show();
                }
            } catch (Throwable t) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(title);
                alert.setContentText(content);
                alert.show();
            }
        });
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}