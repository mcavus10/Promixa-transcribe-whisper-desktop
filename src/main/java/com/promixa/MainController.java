package com.promixa;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.concurrent.Task;
import javafx.application.Platform;
import javafx.application.HostServices;
import javafx.scene.paint.Color;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

// JSON parsing
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonException;

public class MainController {

    @FXML
    private VBox dropArea;

    @FXML
    private Label dropLabel;

    @FXML
    private Button transcribeButton;

    @FXML
    private Button clearButton;

    @FXML
    private Button saveButton;

    @FXML
    private TextArea resultTextArea;

    @FXML
    private ProgressIndicator progressIndicator;
    
    @FXML
    private ComboBox<String> modelComboBox;
    
    @FXML
    private Label statusLabel;
    
    @FXML
    private Hyperlink devPageLink;

    private File selectedAudioFile;
    private String selectedModel = "base";

    private static final String PYTHON_EXECUTABLE = System.getProperty("os.name").toLowerCase().contains("win") ? "python.exe" : "python";
    private static final String SCRIPT_NAME = "whisper_script.py";
    private static final String DEV_PAGE_URL = "https://mcavus.promixa.me";
    
    private static final class ModelOption {
        private final String displayName;
        private final String modelName;
        
        public ModelOption(String modelName, String displayName, String notes) {
            this.modelName = modelName;
            this.displayName = displayName;
            // Not using notes parameter, but keeping it for future use if needed
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    private static final ModelOption[] MODEL_OPTIONS = {
        new ModelOption("tiny", "Tiny (Fast)", "Fastest, lower quality"),
        new ModelOption("base", "Base (Balanced)", "Good balance of speed and quality"),
        new ModelOption("small", "Small (Better)", "Better quality, slower processing"),
        new ModelOption("medium", "Medium (High Quality)", "High quality, slow processing"),
        new ModelOption("large", "Large (Best)", "Best quality, very slow processing")
    };

    @FXML
    public void initialize() {
        transcribeButton.setDisable(true);
        saveButton.setDisable(true);
        progressIndicator.setVisible(false);
        resultTextArea.setEditable(false);
        statusLabel.setText("");
        
        // Populate model combo box
        for (ModelOption option : MODEL_OPTIONS) {
            modelComboBox.getItems().add(option.displayName);
        }
        
        // Default to Base model
        modelComboBox.getSelectionModel().select(1); // Base is at index 1
        selectedModel = "base";
        
        // Listen for model selection changes
        modelComboBox.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.intValue() >= 0 && newVal.intValue() < MODEL_OPTIONS.length) {
                selectedModel = MODEL_OPTIONS[newVal.intValue()].modelName;
                System.out.println("Selected model: " + selectedModel);
            }
        });
        
        // Set up responsive layout behavior
        setupResponsiveLayout();
    }
    
    /**
     * Sets up responsive behavior for the application layout
     */
    private void setupResponsiveLayout() {
        // Add scene listener since scene may not be available during initialization
        dropArea.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                // Set proportional width once scene is available
                dropArea.prefWidthProperty().bind(newScene.widthProperty().multiply(0.8));
                
                // Make result text area responsive
                resultTextArea.prefWidthProperty().bind(newScene.widthProperty().multiply(0.8));
                
                // Dynamic height adjustment based on window width
                newScene.widthProperty().addListener((obs, oldWidth, newWidth) -> {
                    // Adjust for smaller screens
                    if (newWidth.doubleValue() < 600) {
                        dropArea.setMaxHeight(120);
                        // Adjust font sizes for smaller screens
                        statusLabel.setStyle("-fx-font-size: 11px;");
                    } else {
                        dropArea.setMaxHeight(150);
                        statusLabel.setStyle("-fx-font-size: 13px;");
                    }
                });
            }
        });
        
        // Ensure proper model combo box sizing
        modelComboBox.setMaxWidth(Double.MAX_VALUE);
        
        // Ensure proper button sizing in responsive layout
        HBox.setHgrow(transcribeButton, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(clearButton, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(saveButton, javafx.scene.layout.Priority.ALWAYS);
    }

    @FXML
    private void handleDragOver(DragEvent event) {
        if (event.getGestureSource() != dropArea && event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        }
        event.consume();
    }

    @FXML
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            List<File> files = db.getFiles();
            if (!files.isEmpty()) {
                processSelectedFile(files.get(0));
                success = true;
            }
        }
        event.setDropCompleted(success);
        event.consume();
    }

    @FXML
    private void handleBrowseFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Audio File");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files", "*.wav", "*.mp3", "*.m4a", "*.flac")
        );
        File file = fileChooser.showOpenDialog(dropArea.getScene().getWindow());
        if (file != null) {
            processSelectedFile(file);
        }
    }

    private void processSelectedFile(File file) {
        selectedAudioFile = file;
        dropLabel.setText("Selected File: " + file.getName());
        transcribeButton.setDisable(false);
        resultTextArea.clear();
        saveButton.setDisable(true);
    }

    @FXML
    private void handleTranscribe() {
        if (selectedAudioFile == null) {
            showErrorAlert("No file selected", "Please select an audio file first.");
            return;
        }
    
        progressIndicator.setVisible(true);
        transcribeButton.setDisable(true);
        clearButton.setDisable(true);
        saveButton.setDisable(true);
        resultTextArea.setPromptText("Transcription in progress...");
        resultTextArea.clear();
    
        Task<String> transcriptionTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                Path scriptPath = getScriptPath();
                if (scriptPath == null) {
                    throw new IOException("Could not find the transcription script: " + SCRIPT_NAME);
                }
    
                String device = "cpu";
                ProcessBuilder pb = new ProcessBuilder(
                        PYTHON_EXECUTABLE,
                        scriptPath.toString(),
                        selectedAudioFile.getAbsolutePath(),
                        "--model", selectedModel,
                        "--device", device
                );
                pb.redirectErrorStream(true);
    
                System.out.println("Executing command: " + String.join(" ", pb.command()));
    
                Process process = pb.start();
    
                StringBuilder output = new StringBuilder();
                StringBuilder progressMessages = new StringBuilder();
                StringBuilder lastProgressUpdate = new StringBuilder();
    
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            System.out.println("Script output: " + line);
    
                            try {
                                if (line.startsWith("{") && line.endsWith("}")) {
                                    try (JsonReader jsonReader = Json.createReader(new StringReader(line))) {
                                        JsonObject json = jsonReader.readObject();
                                        String message = json.getString("message", "");
                                        String status = json.getString("status", "info");
    
                                        if (!message.isEmpty()) {
                                            lastProgressUpdate.setLength(0);
                                            lastProgressUpdate.append(message);
    
                                            updateMessage(message);
                                            Platform.runLater(() -> {
                                                switch (status) {
                                                    case "error":
                                                        statusLabel.setTextFill(Color.RED);
                                                        break;
                                                    case "complete":
                                                        statusLabel.setTextFill(Color.GREEN);
                                                        break;
                                                    case "working":
                                                    case "loading":
                                                        statusLabel.setTextFill(Color.BLUE);
                                                        break;
                                                    default:
                                                        statusLabel.setTextFill(Color.BLACK);
                                                }
                                            });
                                        }
                                        // Sadece error ise hata fırlat
                                        if ("error".equals(status)) {
                                            throw new IOException(message);
                                        }
                                        // complete ise transcript bir sonraki satırda
                                        if ("complete".equals(status)) {
                                            String transcriptLine = reader.readLine();
                                            if (transcriptLine != null && !transcriptLine.trim().isEmpty()) {
                                                output.setLength(0);
                                                output.append(transcriptLine.trim());
                                            }
                                            continue;
                                        }
                                    }
                                } else if (!line.startsWith("Error")) {
                                    output.setLength(0);
                                    output.append(line.trim());
                                }
                            } catch (JsonException e) {
                                System.out.println("Cannot parse JSON: " + e.getMessage());
                                if (!line.startsWith("Error")) {
                                    output.setLength(0);
                                    output.append(line.trim());
                                }
                            } catch (Exception e) {
                                System.out.println("Error handling output: " + e.getMessage());
                            }
    
                            progressMessages.append(line).append(System.lineSeparator());
                        }
                    }
                }
    
                int exitCode = process.waitFor();
                System.out.println("Script finished with exit code: " + exitCode);
                System.out.println("Full script output:\n" + progressMessages.toString());
    
                if (exitCode != 0) {
                    String errorMsg = extractErrorMessage(progressMessages.toString());
                    if (errorMsg.isEmpty()){
                        errorMsg = "Transcription script failed with exit code " + exitCode + ". Check logs for details.";
                    }
                    throw new IOException(errorMsg);
                }
    
                if (output.length() == 0) {
                    String fullOutput = progressMessages.toString();
                    throw new IOException("Transcription script finished but produced no usable result. Check logs.");
                }
    
                return output.toString();
            }
        };

        // Add listener for progress messages
        transcriptionTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && !newMsg.isEmpty()) {
                Platform.runLater(() -> {
                    statusLabel.setText(newMsg);
                    resultTextArea.setPromptText("Processing...");
                });
            }
        });

        transcriptionTask.setOnSucceeded(event -> {
            String transcriptionResult = transcriptionTask.getValue();
            resultTextArea.setText(transcriptionResult);
            resultTextArea.setPromptText("Transcription results will appear here...");
            statusLabel.setText("Transcription complete!");
            resetUIState(false);
            System.out.println("Transcription successful.");
        });

        transcriptionTask.setOnFailed(event -> {
            Throwable exception = transcriptionTask.getException();
            String errorMessage = "Transcription failed: " + (exception != null ? exception.getMessage() : "Unknown error");
            showErrorAlert("Transcription Error", errorMessage);
            resultTextArea.setPromptText("Transcription failed. Please try again.");
            statusLabel.setText("Error: Transcription failed");
            statusLabel.setTextFill(Color.RED);
            resetUIState(true);
            System.err.println(errorMessage);
            if (exception != null) {
                exception.printStackTrace();
            }
        });

        new Thread(transcriptionTask).start();
    }

    private Path getScriptPath() {
        try {
            File jarDir = new File(MainApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
            Path directPath = Paths.get(jarDir.getAbsolutePath(), SCRIPT_NAME);

            File scriptInPythonSubfolder = new File(jarDir, "python" + File.separator + SCRIPT_NAME);
            if(scriptInPythonSubfolder.exists()){
                System.out.println("Found script in python subfolder: " + scriptInPythonSubfolder.getAbsolutePath());
                return scriptInPythonSubfolder.toPath();
            }

            if (directPath.toFile().exists()) {
                System.out.println("Found script next to JAR: " + directPath.toString());
                return directPath;
            }
            System.out.println("Script not found next to JAR or in python subfolder. Checking resources...");

            URL resourceUrl = getClass().getResource("/" + SCRIPT_NAME);
            if (resourceUrl == null) {
                resourceUrl = getClass().getResource("/python/" + SCRIPT_NAME);
            }

            if (resourceUrl != null) {
                System.out.println("Found script in resources: " + resourceUrl.toURI().toString());
                return Paths.get(resourceUrl.toURI());
            }

            System.err.println("Script not found using JAR location or classpath resources.");
            return null;

        } catch (URISyntaxException | NullPointerException e) {
            System.err.println("Error finding script path: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private String extractErrorMessage(String processOutput) {
        String[] lines = processOutput.split(System.lineSeparator());
        String lastErrorLine = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.toLowerCase().startsWith("error:")) {
                return line.substring("error:".length()).trim();
            }
            if (!line.isEmpty() && !line.toLowerCase().contains("model loaded") && !line.toLowerCase().contains("starting transcription") && !line.toLowerCase().contains("transcription complete")) {
                lastErrorLine = line;
            }
        }
        return lastErrorLine;
    }

    @FXML
    private void handleClear() {
        selectedAudioFile = null;
        dropLabel.setText("Drag & Drop Audio File Here or Click Browse");
        resultTextArea.clear();
        resultTextArea.setPromptText("Transcription results will appear here...");
        transcribeButton.setDisable(true);
        saveButton.setDisable(true);
        progressIndicator.setVisible(false);
        statusLabel.setText("");
        statusLabel.setTextFill(Color.BLACK);
    }
    
    @FXML
    private void openDevPage() {
        try {
            // Use Desktop API instead of HostServices - more reliable cross-platform solution
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(DEV_PAGE_URL));
            } else {
                // Fallback to HostServices if Desktop API is not supported
                HostServices hostServices = (HostServices) transcribeButton.getScene().getWindow().getProperties().get("hostServices");
                if (hostServices != null) {
                    hostServices.showDocument(DEV_PAGE_URL);
                } else {
                    showErrorAlert("Browser Error", "Could not open browser. Please visit " + DEV_PAGE_URL + " manually.");
                }
            }
        } catch (Exception e) {
            showErrorAlert("Browser Error", "Could not open browser: " + e.getMessage());
        }
    }

    @FXML
    private void handleSave() {
        if (resultTextArea.getText().isEmpty()) {
            showErrorAlert("Nothing to Save", "The transcription result is empty.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Transcription");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));

        if (selectedAudioFile != null) {
            String originalName = selectedAudioFile.getName();
            int dotIndex = originalName.lastIndexOf('.');
            String baseName = (dotIndex == -1) ? originalName : originalName.substring(0, dotIndex);
            fileChooser.setInitialFileName(baseName + "_transcription.txt");
        }

        File file = fileChooser.showSaveDialog(dropArea.getScene().getWindow());
        if (file != null) {
            try {
                java.nio.file.Files.writeString(file.toPath(), resultTextArea.getText());
            } catch (IOException e) {
                showErrorAlert("Save Failed", "Could not save the transcription to the file: " + e.getMessage());
            }
        }
    }

    private void resetUIState(boolean enableTranscribe) {
        progressIndicator.setVisible(false);
        transcribeButton.setDisable(enableTranscribe ? (selectedAudioFile == null) : true);
        clearButton.setDisable(false);
        saveButton.setDisable(resultTextArea.getText().isEmpty());
        modelComboBox.setDisable(false); // Enable model selection
    }

    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message.length() > 500 ? message.substring(0, 500) + "..." : message);
        alert.showAndWait();
    }
}
