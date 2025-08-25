package com.simlab.ug.client;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Dialog for editing VTU file group properties
 */
public class EditGroupDialog extends Dialog<VtuFileGroup> {
    
    private VtuFileGroup fileGroup;
    private TextField groupNameField;
    private TextField patternField;
    private TextArea filesPreview;
    
    public EditGroupDialog(VtuFileGroup group) {
        this.fileGroup = group;
        
        setTitle("Edit File Group");
        setHeaderText("Edit group properties and file selection pattern");
        
        // Set dialog buttons
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        // Create content
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));
        
        // Group name
        Label nameLabel = new Label("Group Name:");
        groupNameField = new TextField(group.getGroupName());
        groupNameField.setPrefWidth(300);
        grid.add(nameLabel, 0, 0);
        grid.add(groupNameField, 1, 0);
        
        // Pattern
        Label patternLabel = new Label("File Pattern:");
        patternField = new TextField(group.getPattern());
        patternField.setPrefWidth(300);
        patternField.setPromptText("e.g., *sol_PAN_*.vtu");
        grid.add(patternLabel, 0, 1);
        grid.add(patternField, 1, 1);
        
        // Help text
        Label helpLabel = new Label("Use * as wildcard. Pattern implicitly filters *.vtu files.");
        helpLabel.setStyle("-fx-font-size: 10; -fx-text-fill: gray;");
        grid.add(helpLabel, 1, 2);
        
        // Files preview
        Label previewLabel = new Label("Matching Files:");
        filesPreview = new TextArea();
        filesPreview.setEditable(false);
        filesPreview.setPrefRowCount(8);
        filesPreview.setPrefWidth(400);
        updateFilesPreview();
        
        VBox previewBox = new VBox(5, previewLabel, filesPreview);
        grid.add(previewBox, 0, 3, 2, 1);
        
        // Statistics
        Label statsLabel = new Label(String.format(
            "Total files: %d | Time series: %s", 
            group.getFileCount(),
            group.isTimeSeries() ? "Yes" : "No"
        ));
        statsLabel.setStyle("-fx-font-weight: bold;");
        grid.add(statsLabel, 0, 4, 2, 1);
        
        getDialogPane().setContent(grid);
        
        // Listen for pattern changes
        patternField.textProperty().addListener((obs, oldVal, newVal) -> {
            // Note: In real implementation, this would trigger a rescan
            // For now, just show a note
            if (!newVal.equals(group.getPattern())) {
                statsLabel.setText("Pattern changed - rescan required after OK");
            }
        });
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                fileGroup.setGroupName(groupNameField.getText());
                fileGroup.setPattern(patternField.getText());
                return fileGroup;
            }
            return null;
        });
    }
    
    private void updateFilesPreview() {
        StringBuilder preview = new StringBuilder();
        int maxFiles = 10;
        int count = 0;
        
        for (VtuFileGroup.VtuFile file : fileGroup.getFiles()) {
            if (count >= maxFiles) {
                preview.append("... and ").append(fileGroup.getFileCount() - maxFiles)
                       .append(" more files\n");
                break;
            }
            preview.append(file.getFilename());
            if (file.hasTimeStep()) {
                preview.append(" (t=").append(file.getTimeStep()).append(")");
            }
            preview.append("\n");
            count++;
        }
        
        if (fileGroup.getFileCount() == 0) {
            preview.append("No files matching pattern");
        }
        
        filesPreview.setText(preview.toString());
    }
}