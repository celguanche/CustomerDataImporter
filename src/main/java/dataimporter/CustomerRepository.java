package dataimporter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;

public final class CustomerRepository {

    private static final String FIND_SQL =
            "SELECT dni, apellidos, cp, nombre, email, ciudad FROM clientes WHERE dni = ?";

    private static final String INSERT_SQL =
            "INSERT INTO clientes (dni, apellidos, cp, nombre, email, ciudad) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE clientes SET apellidos = ?, cp = ?, nombre = ?, email = ?, ciudad = ? " +
                    "WHERE dni = ?";

    private Connection connect() throws SQLException {
        String url = System.getenv().getOrDefault(
                "IMPORT_DB_URL",
                "jdbc:mysql://localhost:3306/customer_importer"
        );

        String user = System.getenv("IMPORT_DB_USER");
        String password = System.getenv("IMPORT_DB_PASSWORD");

        if (user == null || password == null) {
            throw new SQLException(
                    "Configura IMPORT_DB_USER e IMPORT_DB_PASSWORD antes de iniciar la aplicación."
            );
        }

        return DriverManager.getConnection(url, user, password);
    }

    public void preview(List<ImportRow> rows) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement find = connection.prepareStatement(FIND_SQL)) {

            for (ImportRow row : rows) {
                if (row.status() != ImportRow.Status.NEW) {
                    continue;
                }

                Customer existing = findByDni(find, row.incoming().dni());

                if (existing != null) {
                    row.match(existing);
                }
            }
        }
    }

    private Customer findByDni(PreparedStatement statement, String dni)
            throws SQLException {
        statement.setString(1, dni);

        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }

            return new Customer(
                    result.getString("dni"),
                    result.getString("apellidos"),
                    result.getString("cp"),
                    result.getString("nombre"),
                    result.getString("email"),
                    result.getString("ciudad")
            );
        }
    }

    public void save(List<ImportRow> rows) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement(INSERT_SQL);
             PreparedStatement update = connection.prepareStatement(UPDATE_SQL);
             PreparedStatement lock = connection.prepareStatement(FIND_SQL + " FOR UPDATE")) {

            connection.setAutoCommit(false);

            for (ImportRow row : rows) {
                if (row.status() == ImportRow.Status.EXISTING
                        && row.decision() == ImportRow.Decision.KEEP) {
                    row.finish(ImportRow.Status.KEPT, "Se conservaron los datos de MySQL.");
                    continue;
                }

                if (row.status() != ImportRow.Status.NEW
                        && row.status() != ImportRow.Status.EXISTING) {
                    continue;
                }

                try {
                    if (row.status() == ImportRow.Status.NEW) {
                        insertCustomer(insert, row.incoming());
                        connection.commit();
                        row.finish(ImportRow.Status.INSERTED, "Cliente insertado.");
                    } else {
                        updateExisting(connection, lock, update, row);
                    }
                } catch (SQLException ex) {
                    connection.rollback();

                    if (isRowError(ex)) {
                        String reason = ex.getErrorCode() == 1062
                                ? "El DNI ya existe. Vuelve a cargar el CSV para compararlo."
                                : "MySQL rechazó esta fila. " + ex.getMessage();

                        row.finish(ImportRow.Status.REJECTED, reason);
                    } else {
                        throw ex;
                    }
                }
            }
        }
    }

    private void insertCustomer(PreparedStatement insert, Customer customer)
            throws SQLException {
        insert.setString(1, customer.dni());
        insert.setString(2, customer.surname());
        insert.setObject(3, customer.postalCode(), Types.CHAR);
        insert.setString(4, customer.firstName());
        insert.setString(5, customer.email());
        insert.setString(6, customer.city());
        insert.executeUpdate();
    }

    private void updateExisting(Connection connection, PreparedStatement lock,
                                PreparedStatement update, ImportRow row)
            throws SQLException {
        Customer current = findByDni(lock, row.incoming().dni());

        if (!Objects.equals(current, row.existing())) {
            connection.rollback();
            row.finish(ImportRow.Status.REJECTED,
                    "El cliente cambió desde la vista previa. Vuelve a cargar esta fila.");
            return;
        }

        Customer merged = row.incoming().mergeWith(current);

        if (merged.equals(current)) {
            connection.commit();
            row.finish(ImportRow.Status.KEPT, "Los datos ya coincidían.");
            return;
        }

        update.setString(1, merged.surname());
        update.setObject(2, merged.postalCode(), Types.CHAR);
        update.setString(3, merged.firstName());
        update.setString(4, merged.email());
        update.setString(5, merged.city());
        update.setString(6, merged.dni());
        update.executeUpdate();

        connection.commit();
        row.finish(ImportRow.Status.UPDATED, "Cliente actualizado.");
    }

    private boolean isRowError(SQLException ex) {
        String state = ex.getSQLState();
        return state != null && (state.startsWith("22") || state.startsWith("23"));
    }
}