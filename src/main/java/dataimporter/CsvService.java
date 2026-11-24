package dataimporter;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CsvService {

    private static final String OLD_HEADER = "dni;apellidos;cp";
    private static final String HEADER = OLD_HEADER + ";nombre;email;ciudad";

    public record Batch(String header, List<ImportRow> rows) {}

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setIgnoreEmptyLines(false)
            .build();

    public Batch read(Path file) throws IOException {
        List<ImportRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String header;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            header = reader.readLine();

            if (header != null && header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }

            boolean legacy = OLD_HEADER.equals(header);

            if (!legacy && !HEADER.equals(header)) {
                throw new IOException("Cabecera esperada: " + HEADER + " o " + OLD_HEADER);
            }

            int lineNumber = 1;
            String line;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                try {
                    Customer customer = parseCustomer(line, legacy);

                    if (!seen.add(customer.dni())) {
                        throw new IllegalArgumentException("DNI repetido dentro del CSV.");
                    }

                    rows.add(ImportRow.valid(lineNumber, line, customer));
                } catch (IllegalArgumentException | IOException ex) {
                    rows.add(ImportRow.rejected(lineNumber, line, ex.getMessage()));
                }
            }
        }

        return new Batch(header, rows);
    }

    private Customer parseCustomer(String line, boolean legacy) throws IOException {
        int expectedColumns = legacy ? 3 : 6;

        try (CSVParser parser = CSVParser.parse(line, FORMAT)) {
            List<CSVRecord> records = parser.getRecords();

            if (records.size() != 1 || records.get(0).size() != expectedColumns) {
                throw new IllegalArgumentException(
                        "Se esperaban " + expectedColumns + " columnas según la cabecera."
                );
            }

            CSVRecord record = records.get(0);

            return Customer.fromCsv(
                    record.get(0), record.get(1), record.get(2),
                    legacy ? "" : record.get(3),
                    legacy ? "" : record.get(4),
                    legacy ? "" : record.get(5)
            );
        } catch (java.io.UncheckedIOException ex) {
            throw new IOException("El formato CSV de la fila es incorrecto.", ex);
        }
    }

    public void exportRejected(Path destination, List<ImportRow> rows, String header)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(destination,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write(header);
            writer.newLine();

            for (ImportRow row : rows) {
                if (row.status() == ImportRow.Status.REJECTED) {
                    writer.write(row.originalLine());
                    writer.newLine();
                }
            }
        }
    }

    public Path appendLog(Path source, List<ImportRow> rows) throws IOException {
        Path directory = Path.of("logs").toAbsolutePath();
        Files.createDirectories(directory);

        Path log = directory.resolve("no_insertados.log");

        try (BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (ImportRow row : rows) {
                if (row.status() == ImportRow.Status.REJECTED) {
                    writer.write("%s | %s | línea %d | %s | %s".formatted(
                            OffsetDateTime.now(),
                            source.getFileName(),
                            row.lineNumber(),
                            row.detail(),
                            row.originalLine()
                    ));
                    writer.newLine();
                }
            }
        }

        return log;
    }
}