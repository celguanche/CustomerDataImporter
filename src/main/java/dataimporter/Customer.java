package dataimporter;

import java.util.Locale;

public record Customer(
        String dni,
        String surname,
        String postalCode,
        String firstName,
        String email,
        String city
) {
    private static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

    public static Customer fromCsv(
            String rawDni, String rawSurname, String rawPostalCode,
            String rawFirstName, String rawEmail, String rawCity
    ) {
        String dni = rawDni.strip().toUpperCase(Locale.ROOT);
        String surname = rawSurname.strip();
        String postalCode = rawPostalCode.strip();

        if (!dni.matches("[0-9]{8}[A-Z]")) {
            throw new IllegalArgumentException(
                    "El DNI debe contener ocho números y una letra."
            );
        }

        int number = Integer.parseInt(dni.substring(0, 8));
        if (dni.charAt(8) != DNI_LETTERS.charAt(number % 23)) {
            throw new IllegalArgumentException("La letra de control del DNI es incorrecta.");
        }

        if (surname.isEmpty() || surname.codePointCount(0, surname.length()) > 100) {
            throw new IllegalArgumentException(
                    "Los apellidos deben tener entre 1 y 100 caracteres."
            );
        }

        if (!postalCode.isEmpty() && !postalCode.matches("[0-9]{5}")) {
            throw new IllegalArgumentException(
                    "El código postal debe tener cinco números o quedar vacío."
            );
        }

        String firstName = optionalText(rawFirstName, 80, "nombre");
        String email = optionalText(rawEmail, 254, "correo");
        String city = optionalText(rawCity, 100, "ciudad");

        if (email != null && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("El correo electrónico no es válido.");
        }

        return new Customer(dni, surname, postalCode.isEmpty() ? null : postalCode,
                firstName, email, city);
    }

    private static String optionalText(String raw, int limit, String label) {
        String value = raw.strip();

        if (value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException("Longitud excesiva en " + label + ".");
        }

        return value.isEmpty() ? null : value;
    }

    public Customer mergeWith(Customer existing) {
        return new Customer(
                dni,
                surname,
                postalCode == null ? existing.postalCode() : postalCode,
                firstName == null ? existing.firstName() : firstName,
                email == null ? existing.email() : email,
                city == null ? existing.city() : city
        );
    }
}