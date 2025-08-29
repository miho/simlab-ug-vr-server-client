package com.simlab.ug.client;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.jpro.webapi.WebAPI;
import javafx.scene.Node;
import javafx.geometry.Pos;
import java.io.File;
import java.util.Map;

/**
 * Dialog for configuring VTU to GLTF conversion options
 */
public class VtuConversionDialog extends Dialog<Map<String, String>> {
    
    private VtuFileGroup fileGroup;
    private GridPane grid;
    private int currentRow = 0;
    
    // Controls for various options
    private TextField exportField;
    private CheckBox noPreviewCheck;
    private TextField widthField;
    private TextField heightField;
    private TextField bgColorField;
    private TextField colorArrayField;
    private ComboBox<String> colorAssocCombo;
    private TextField opacityField;
    private CheckBox contourCheck;
    private TextField contourArrayField;
    private ComboBox<String> contourAssocCombo;
    private TextField isoValuesField;
    private TextField isoCountField;
    private TextField glyphArrayField;
    private ComboBox<String> glyphAssocCombo;
    private TextField glyphScaleField;
    private TextField glyphEveryField;
    private TextField noMeshField;
    
    public VtuConversionDialog(VtuFileGroup fileGroup) {
        this.fileGroup = fileGroup;
        
        setTitle("Configure VTU to GLTF Conversion");
        setHeaderText("Group: " + fileGroup.getGroupName() + " (" + fileGroup.getFileCount() + " files)");
        
        // Set dialog buttons
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        // Create content
        grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));
        
        // Add sections
        addBasicOptions();
        addSeparator();
        addSurfaceOptions();
        addSeparator();
        addContourOptions();
        addSeparator();
        addGlyphOptions();
        addSeparator();
        addMeshOptions();
        
        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(500);
        
        getDialogPane().setContent(scrollPane);
        
        // Load current options
        loadCurrentOptions();
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                return collectOptions();
            }
            return null;
        });
    }
    
    private void addBasicOptions() {
        addSectionLabel("Basic Options");
        
        // Export path
        Label exportLabel = new Label("Export Path:");
        exportField = new TextField();
        exportField.setPrefWidth(250);
        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> browseForExportPath());
        
        HBox exportBox = new HBox(5, exportField, browseButton);
        grid.add(exportLabel, 0, currentRow);
        grid.add(exportBox, 1, currentRow++, 2, 1);
        
        // Preview options
        noPreviewCheck = new CheckBox("Disable Preview");
        grid.add(noPreviewCheck, 0, currentRow++, 2, 1);
        
        // Window dimensions
        Label widthLabel = new Label("Preview Width:");
        widthField = new TextField("1280");
        widthField.setPrefWidth(80);
        
        Label heightLabel = new Label("Height:");
        heightField = new TextField("720");
        heightField.setPrefWidth(80);
        
        HBox dimensionBox = new HBox(10, widthField, heightLabel, heightField);
        grid.add(widthLabel, 0, currentRow);
        grid.add(dimensionBox, 1, currentRow++, 2, 1);
        
        // Background color
        Label bgLabel = new Label("Background Color:");
        bgColorField = new TextField("0.08,0.09,0.10");
        bgColorField.setPromptText("r,g,b (0..1 or 0..255)");
        grid.add(bgLabel, 0, currentRow);
        grid.add(bgColorField, 1, currentRow++);
    }
    
    private void addSurfaceOptions() {
        addSectionLabel("Surface Options");
        
        // Color array
        Label colorArrayLabel = new Label("Color Array:");
        colorArrayField = new TextField();
        colorArrayField.setPromptText("Array name for surface coloring");
        grid.add(colorArrayLabel, 0, currentRow);
        grid.add(colorArrayField, 1, currentRow++);
        
        // Color association
        Label colorAssocLabel = new Label("Color Association:");
        colorAssocCombo = new ComboBox<>();
        colorAssocCombo.getItems().addAll("auto", "point", "cell");
        colorAssocCombo.setValue("auto");
        grid.add(colorAssocLabel, 0, currentRow);
        grid.add(colorAssocCombo, 1, currentRow++);
        
        // Opacity
        Label opacityLabel = new Label("Opacity:");
        opacityField = new TextField("1.0");
        opacityField.setPromptText("0.0 - 1.0");
        grid.add(opacityLabel, 0, currentRow);
        grid.add(opacityField, 1, currentRow++);
    }
    
    private void addContourOptions() {
        addSectionLabel("Contour Options");
        
        // Enable contour
        contourCheck = new CheckBox("Generate Contour Surfaces");
        grid.add(contourCheck, 0, currentRow++, 2, 1);
        
        // Contour array
        Label contourArrayLabel = new Label("Contour Array:");
        contourArrayField = new TextField();
        contourArrayField.setPromptText("Scalar or vector array");
        grid.add(contourArrayLabel, 0, currentRow);
        grid.add(contourArrayField, 1, currentRow++);
        
        // Contour association
        Label contourAssocLabel = new Label("Contour Association:");
        contourAssocCombo = new ComboBox<>();
        contourAssocCombo.getItems().addAll("auto", "point", "cell");
        contourAssocCombo.setValue("auto");
        grid.add(contourAssocLabel, 0, currentRow);
        grid.add(contourAssocCombo, 1, currentRow++);
        
        // Iso values
        Label isoLabel = new Label("Iso Values:");
        isoValuesField = new TextField();
        isoValuesField.setPromptText("v1,v2,v3 (explicit values)");
        grid.add(isoLabel, 0, currentRow);
        grid.add(isoValuesField, 1, currentRow++);
        
        // Iso count
        Label isoCountLabel = new Label("Auto Iso Count:");
        isoCountField = new TextField("5");
        isoCountField.setPromptText("Number of iso-surfaces");
        grid.add(isoCountLabel, 0, currentRow);
        grid.add(isoCountField, 1, currentRow++);
        
        // Enable/disable contour fields based on checkbox
        contourArrayField.disableProperty().bind(contourCheck.selectedProperty().not());
        contourAssocCombo.disableProperty().bind(contourCheck.selectedProperty().not());
        isoValuesField.disableProperty().bind(contourCheck.selectedProperty().not());
        isoCountField.disableProperty().bind(contourCheck.selectedProperty().not());
    }
    
    private void addGlyphOptions() {
        addSectionLabel("Glyph Options");
        
        // Glyph array
        Label glyphArrayLabel = new Label("Glyph Array:");
        glyphArrayField = new TextField();
        glyphArrayField.setPromptText("Vector array for glyphs");
        grid.add(glyphArrayLabel, 0, currentRow);
        grid.add(glyphArrayField, 1, currentRow++);
        
        // Glyph association
        Label glyphAssocLabel = new Label("Glyph Association:");
        glyphAssocCombo = new ComboBox<>();
        glyphAssocCombo.getItems().addAll("auto", "point", "cell");
        glyphAssocCombo.setValue("auto");
        grid.add(glyphAssocLabel, 0, currentRow);
        grid.add(glyphAssocCombo, 1, currentRow++);
        
        // Glyph scale
        Label glyphScaleLabel = new Label("Glyph Scale:");
        glyphScaleField = new TextField("0.05");
        glyphScaleField.setPromptText("Scale factor");
        grid.add(glyphScaleLabel, 0, currentRow);
        grid.add(glyphScaleField, 1, currentRow++);
        
        // Glyph every N
        Label glyphEveryLabel = new Label("Glyph Every:");
        glyphEveryField = new TextField("1");
        glyphEveryField.setPromptText("Show every Nth point");
        grid.add(glyphEveryLabel, 0, currentRow);
        grid.add(glyphEveryField, 1, currentRow++);
    }
    
    private void addMeshOptions() {
        addSectionLabel("Mesh Options");
        
        // No mesh
        Label noMeshLabel = new Label("Disable Mesh:");
        noMeshField = new TextField();
        noMeshField.setPromptText("all, or indices/names (CSV)");
        grid.add(noMeshLabel, 0, currentRow);
        grid.add(noMeshField, 1, currentRow++);
    }
    
    private void addSectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");
        grid.add(label, 0, currentRow++, 3, 1);
    }
    
    private void addSeparator() {
        Separator separator = new Separator();
        grid.add(separator, 0, currentRow++, 3, 1);
    }
    
    private void browseForExportPath() {
        Stage stage = (Stage) getDialogPane().getScene().getWindow();
        WebAPI webAPI = WebAPI.getWebAPI(stage);
        if (webAPI != null) {
            // JPro mode - use web workaround
            TextField input = new TextField(exportField.getText().isEmpty() ? 
                fileGroup.getGroupName() + ".gltf" : exportField.getText());
            input.setPrefWidth(520);
            Button ok = new Button("OK");
            Button cancel = new Button("Cancel");
            HBox actions = new HBox(10, cancel, ok);
            actions.setAlignment(Pos.CENTER_RIGHT);
            VBox card = new VBox(12, new Label("Enter export file path (.gltf or .glb)"), input, actions);
            card.setMaxWidth(640);
            card.setPadding(new Insets(18));
            card.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 24,0,0,8);");
            Runnable close = openOverlay(card);
            ok.setOnAction(e -> { 
                exportField.setText(input.getText());
                close.run(); 
            });
            cancel.setOnAction(e -> close.run());
        } else {
            // Native JavaFX mode - use native dialog
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select Export Path");
            fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("GLTF Files", "*.gltf"),
                new FileChooser.ExtensionFilter("GLB Files", "*.glb")
            );
            fileChooser.setInitialFileName(fileGroup.getGroupName() + ".gltf");
            
            File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
            if (file != null) {
                exportField.setText(file.getAbsolutePath());
            }
        }
    }
    
    // Helper method for overlay in JPro mode
    private Runnable openOverlay(Node content) {
        javafx.scene.Scene scene = getDialogPane().getScene();
        if (scene != null && scene.getRoot() instanceof StackPane) {
            StackPane appRoot = (StackPane) scene.getRoot();
            StackPane overlay = new StackPane();
            overlay.setPickOnBounds(true);
            overlay.setStyle("-fx-background-color: rgba(0,0,0,0.45);");
            StackPane.setAlignment(content, Pos.CENTER);
            content.setOnMouseClicked(ev -> ev.consume());
            overlay.getChildren().add(content);
            appRoot.getChildren().add(overlay);
            Runnable close = () -> appRoot.getChildren().remove(overlay);
            overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) { e.consume(); close.run(); } });
            overlay.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) close.run(); });
            overlay.requestFocus();
            return close;
        }
        return () -> {};
    }
    
    private void loadCurrentOptions() {
        Map<String, String> options = fileGroup.getConversionOptions();
        
        exportField.setText(options.getOrDefault("export", "scene.gltf"));
        noPreviewCheck.setSelected("true".equals(options.get("no-preview")));
        widthField.setText(options.getOrDefault("width", "1280"));
        heightField.setText(options.getOrDefault("height", "720"));
        bgColorField.setText(options.getOrDefault("bg", "0.08,0.09,0.10"));
        colorArrayField.setText(options.getOrDefault("color-array", ""));
        colorAssocCombo.setValue(options.getOrDefault("color-assoc", "auto"));
        opacityField.setText(options.getOrDefault("opacity", "1.0"));
        contourCheck.setSelected("true".equals(options.get("contour")));
        contourArrayField.setText(options.getOrDefault("contour-array", ""));
        contourAssocCombo.setValue(options.getOrDefault("contour-assoc", "auto"));
        isoValuesField.setText(options.getOrDefault("iso", ""));
        isoCountField.setText(options.getOrDefault("iso-count", "5"));
        glyphArrayField.setText(options.getOrDefault("glyph-array", ""));
        glyphAssocCombo.setValue(options.getOrDefault("glyph-assoc", "auto"));
        glyphScaleField.setText(options.getOrDefault("glyph-scale", "0.05"));
        glyphEveryField.setText(options.getOrDefault("glyph-every", "1"));
        noMeshField.setText(options.getOrDefault("no-mesh", ""));
    }
    
    private Map<String, String> collectOptions() {
        Map<String, String> options = fileGroup.getConversionOptions();
        
        // Basic options
        setOptionIfNotEmpty(options, "export", exportField.getText());
        options.put("no-preview", noPreviewCheck.isSelected() ? "true" : "false");
        setOptionIfNotEmpty(options, "width", widthField.getText());
        setOptionIfNotEmpty(options, "height", heightField.getText());
        setOptionIfNotEmpty(options, "bg", bgColorField.getText());
        
        // Surface options
        setOptionIfNotEmpty(options, "color-array", colorArrayField.getText());
        options.put("color-assoc", colorAssocCombo.getValue());
        setOptionIfNotEmpty(options, "opacity", opacityField.getText());
        
        // Contour options
        options.put("contour", contourCheck.isSelected() ? "true" : "false");
        if (contourCheck.isSelected()) {
            setOptionIfNotEmpty(options, "contour-array", contourArrayField.getText());
            options.put("contour-assoc", contourAssocCombo.getValue());
            setOptionIfNotEmpty(options, "iso", isoValuesField.getText());
            setOptionIfNotEmpty(options, "iso-count", isoCountField.getText());
        }
        
        // Glyph options
        setOptionIfNotEmpty(options, "glyph-array", glyphArrayField.getText());
        if (!glyphArrayField.getText().isEmpty()) {
            options.put("glyph-assoc", glyphAssocCombo.getValue());
            setOptionIfNotEmpty(options, "glyph-scale", glyphScaleField.getText());
            setOptionIfNotEmpty(options, "glyph-every", glyphEveryField.getText());
        }
        
        // Mesh options
        setOptionIfNotEmpty(options, "no-mesh", noMeshField.getText());
        
        return options;
    }
    
    private void setOptionIfNotEmpty(Map<String, String> options, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            options.put(key, value.trim());
        } else {
            options.remove(key);
        }
    }
}