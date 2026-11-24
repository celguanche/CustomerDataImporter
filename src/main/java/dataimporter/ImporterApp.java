package dataimporter;

import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ImporterApp extends Application {

    private final CsvService csv = new CsvService();
    private final CustomerRepository repository = new CustomerRepository();

    private final TableView<ImportRow> table = new TableView<>();
    private final Button openButton = new Button("Abrir CSV");
    private final Button importButton = new Button("Importar clientes");
    private final Button exportButton = new Button("Exportar rechazados");

    private final ComboBox<ImportRow.Decision> decisionBox = new ComboBox<>();
    private final Label fileLabel = new Label("Ningún archivo seleccionado");
    private final Label summary = new Label("Abre un CSV para revisar sus clientes.");
    private final Label detail = new Label();

    private Stage stage;
    private Path source;
    private String sourceHeader;
    private List<ImportRow> rows = List.of();

    private boolean busy;
    private boolean finished;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        Label title = new Label("Importador de clientes");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        HBox toolbar = new HBox(10, openButton, importButton, exportButton);
        VBox top = new VBox(10, title, toolbar, fileLabel);

        configureTable();

        decisionBox.getItems().setAll(ImportRow.Decision.values());
        decisionBox.setOnAction(event -> {
            ImportRow selected = table.getSelectionModel().getSelectedItem();
            ImportRow.Decision decision = decisionBox.getValue();

            if (!busy && !finished && selected != null && decision != null
                    && selected.status() == ImportRow.Status.EXISTING
                    && selected.decision() != decision) {
                selected.setDecision(decision);
                table.refresh();
            }
        });

        detail.setWrapText(true);
        summary.setWrapText(true);

        HBox decisionBar = new HBox(
                10, new Label("Decisión para este cliente"), decisionBox
        );
        VBox bottom = new VBox(10, detail, decisionBar, new Separator(), summary);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(18));
        root.setTop(top);
        root.setCenter(table);
        root.setBottom(bottom);
        BorderPane.setMargin(table, new Insets(15, 0, 15, 0));

        openButton.setOnAction(event -> openCsv());
        importButton.setOnAction(event -> importCustomers());
        exportButton.setOnAction(event -> exportRejected());

        table.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> showSelected());

        stage.setTitle("Customer Data Importer");
        stage.setScene(new Scene(root, 1180, 650));

        stage.setOnCloseRequest(event -> {
            if (busy) {
                event.consume();
            }
        });

        refreshControls();
        stage.show();
    }

    private void configureTable() {
        table.setPlaceholder(new Label("Aquí aparecerán las filas del CSV."));

        table.getColumns().add(column("Línea", 60,
                row -> Integer.toString(row.lineNumber())));

        table.getColumns().add(customerColumn("DNI", 105, Customer::dni));
        table.getColumns().add(customerColumn("Nombre", 110, Customer::firstName));
        table.getColumns().add(customerColumn("Apellidos", 160, Customer::surname));
        table.getColumns().add(customerColumn("CP", 65, Customer::postalCode));
        table.getColumns().add(customerColumn("Correo", 210, Customer::email));
        table.getColumns().add(customerColumn("Ciudad", 115, Customer::city));

        table.getColumns().add(column("Estado", 100,
                row -> row.status().toString()));

        table.getColumns().add(column("Decisión", 100,
                row -> row.status() == ImportRow.Status.EXISTING
                        ? row.decision().toString() : ""));

        table.getColumns().add(column("Detalle", 290, ImportRow::detail));
    }

    private TableColumn<ImportRow, String> customerColumn(
            String title, int width, Function<Customer, String> value) {
        return column(title, width, row -> {
            Customer customer = row.incoming();
            return customer == null ? "" : display(value.apply(customer));
        });
    }

    private TableColumn<ImportRow, String> column(
            String title, int width, Function<ImportRow, String> value) {
        TableColumn<ImportRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private void openCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Seleccionar archivo de clientes");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Archivos CSV", "*.csv")
        );

        File selected = chooser.showOpenDialog(stage);
        if (selected == null) {
            return;
        }

        Path candidate = selected.toPath();

        runTask("Leyendo el CSV y comparando los DNI con MySQL…", () -> {
            CsvService.Batch loaded = csv.read(candidate);
            repository.preview(loaded.rows());
            return loaded;
        }, loaded -> {
            source = candidate;
            sourceHeader = loaded.header();
            rows = loaded.rows();
            finished = false;

            table.getItems().setAll(rows);
            fileLabel.setText(source.toAbsolutePath().toString());

            summary.setText(
                    "Filas: %d   |   Nuevos: %d   |   Existentes: %d   |   Errores: %d"
                            .formatted(
                                    rows.size(),
                                    count(ImportRow.Status.NEW),
                                    count(ImportRow.Status.EXISTING),
                                    count(ImportRow.Status.REJECTED)
                            )
            );
        });
    }

    private void importCustomers() {
        List<ImportRow> batch = rows;
        Path batchSource = source;
        finished = true;

        // Detach the rows while the database task changes their states.
        table.getItems().clear();

        runTask("Importando clientes…", () -> {
            String databaseIssue = "";
            String logMessage = "";

            try {
                repository.save(batch);
            } catch (SQLException ex) {
                databaseIssue = "\nImportación interrumpida. Recarga el CSV antes de "
                        + "reintentar. Detalle: " + ex.getMessage();
            }

            boolean hasRejectedRows = batch.stream()
                    .anyMatch(row -> row.status() == ImportRow.Status.REJECTED);

            if (hasRejectedRows) {
                try {
                    Path log = csv.appendLog(batchSource, batch);
                    logMessage = "\nRegistro: " + log;
                } catch (IOException ex) {
                    logMessage = "\nNo se pudo guardar el log: " + ex.getMessage()
                            + ". Puedes exportar las filas rechazadas desde la ventana.";
                }
            }

            return databaseIssue + logMessage;
        }, message -> {
            table.getItems().setAll(batch);

            long pending = count(ImportRow.Status.NEW)
                    + count(ImportRow.Status.EXISTING);

            summary.setText(
                    ("Insertados: %d   |   Actualizados: %d   |   Conservados: %d"
                            + "   |   Rechazados: %d   |   Sin confirmar: %d%s")
                            .formatted(
                                    count(ImportRow.Status.INSERTED),
                                    count(ImportRow.Status.UPDATED),
                                    count(ImportRow.Status.KEPT),
                                    count(ImportRow.Status.REJECTED),
                                    pending,
                                    message
                            )
            );
        });
    }

    private void exportRejected() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar filas rechazadas en un archivo nuevo");
        chooser.setInitialFileName("clientes_rechazados.csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Archivos CSV", "*.csv")
        );

        File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }

        Path destination = selected.toPath();

        runTask("Exportando filas rechazadas…", () -> {
            try {
                csv.exportRejected(destination, rows, sourceHeader);
            } catch (FileAlreadyExistsException ex) {
                throw new IOException(
                        "Ese archivo ya existe. Elige un nombre nuevo.", ex
                );
            }

            return destination;
        }, path -> summary.setText(
                "CSV de rechazados guardado en " + path.toAbsolutePath()
        ));
    }

    private void showSelected() {
        ImportRow row = table.getSelectionModel().getSelectedItem();

        boolean canChoose = !busy && !finished && row != null
                && row.status() == ImportRow.Status.EXISTING;

        decisionBox.setDisable(!canChoose);

        if (row == null) {
            decisionBox.setValue(null);
            detail.setText("Selecciona una fila para ver sus datos.");
            return;
        }

        decisionBox.setValue(row.existing() == null ? null : row.decision());

        if (row.existing() == null) {
            detail.setText(
                    "Línea " + row.lineNumber() + " · " + row.detail()
                            + "\nCSV: " + row.originalLine()
            );
            return;
        }

        Customer existing = row.existing();
        Customer incoming = row.incoming();
        Customer merged = incoming.mergeWith(existing);

        detail.setText(
                "MySQL al revisar: %s%nCSV: %s%nSi actualizas: %s"
                        .formatted(describe(existing), describe(incoming), describe(merged))
        );
    }

    private String describe(Customer customer) {
        return "%s %s · DNI %s · CP %s · %s · %s".formatted(
                display(customer.firstName()),
                display(customer.surname()),
                display(customer.dni()),
                display(customer.postalCode()),
                display(customer.email()),
                display(customer.city())
        );
    }

    private String display(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private long count(ImportRow.Status status) {
        return rows.stream()
                .filter(row -> row.status() == status)
                .count();
    }

    private void refreshControls() {
        openButton.setDisable(busy);
        importButton.setDisable(busy || finished || rows.isEmpty());
        exportButton.setDisable(busy || count(ImportRow.Status.REJECTED) == 0);
        table.setDisable(busy);
        showSelected();
    }

    private <T> void runTask(
            String progress, Callable<T> action, Consumer<T> onSuccess) {
        busy = true;
        summary.setText(progress);
        refreshControls();

        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return action.call();
            }
        };

        task.setOnSucceeded(event -> {
            busy = false;
            onSuccess.accept(task.getValue());
            refreshControls();
        });

        task.setOnFailed(event -> {
            busy = false;
            table.getItems().setAll(rows);
            summary.setText(
                    "No se pudo completar la operación: " + task.getException().getMessage()
            );
            refreshControls();
        });

        Thread worker = new Thread(task, "customer-import-worker");
        worker.setDaemon(true);
        worker.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}