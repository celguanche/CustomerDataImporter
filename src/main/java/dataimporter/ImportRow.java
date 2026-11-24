package dataimporter;

public final class ImportRow {

    public enum Status {
        NEW("Nuevo"),
        EXISTING("Ya existe"),
        REJECTED("Error"),
        INSERTED("Insertado"),
        UPDATED("Actualizado"),
        KEPT("Conservado");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Decision {
        KEEP("Conservar"),
        UPDATE("Actualizar");

        private final String label;

        Decision(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final int lineNumber;
    private final String originalLine;
    private final Customer incoming;

    private Customer existing;
    private Status status;
    private Decision decision = Decision.KEEP;
    private String detail;

    private ImportRow(int lineNumber, String originalLine, Customer incoming,
                      Status status, String detail) {
        this.lineNumber = lineNumber;
        this.originalLine = originalLine;
        this.incoming = incoming;
        this.status = status;
        this.detail = detail;
    }

    public static ImportRow valid(int lineNumber, String line, Customer customer) {
        return new ImportRow(lineNumber, line, customer, Status.NEW,
                "Listo para insertar.");
    }

    public static ImportRow rejected(int lineNumber, String line, String reason) {
        return new ImportRow(lineNumber, line, null, Status.REJECTED, reason);
    }

    public void match(Customer customer) {
        existing = customer;
        status = Status.EXISTING;
        detail = "El DNI ya está en MySQL.";
    }

    public void finish(Status finalStatus, String message) {
        status = finalStatus;
        detail = message;
    }

    public void setDecision(Decision decision) {
        if (status == Status.EXISTING) {
            this.decision = decision;
        }
    }

    public int lineNumber() { return lineNumber; }
    public String originalLine() { return originalLine; }
    public Customer incoming() { return incoming; }
    public Customer existing() { return existing; }
    public Status status() { return status; }
    public Decision decision() { return decision; }
    public String detail() { return detail; }
}