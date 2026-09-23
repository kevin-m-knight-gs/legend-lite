package com.legend.exec;

/**
 * The driver's ONE metadata read (dialect resolution), and the JDBC boundary it
 * names. It lived in {@code Compiler}, whose own comment called it the place
 * "java.sql stops"; it lives here because of what that co-location cost: the JVM
 * verifier resolves a method's {@code catch} clause types when the class is
 * LINKED, so a {@code catch (java.sql.SQLException)} made {@code Compiler} &mdash;
 * the plan surface included &mdash; unloadable without the {@code java.sql}
 * module, even for a caller that only plans (no database, no driver: a WASM or
 * slim-runtime planner). A JDBC type in a method signature resolves lazily and
 * costs nothing; a catch clause takes the whole class with it. Ported from
 * datacube/dual-plane (e5283c551).
 */
public final class JdbcMetadata {

    private JdbcMetadata() {
    }

    /** The product name, or the product version when {@code product} is false. */
    public static String read(java.sql.Connection connection, boolean product) {
        try {
            return product
                    ? connection.getMetaData().getDatabaseProductName()
                    : connection.getMetaData().getDatabaseProductVersion();
        } catch (java.sql.SQLException e) {
            throw new com.legend.error.DataError(String.valueOf(e.getMessage()), e);
        }
    }
}
