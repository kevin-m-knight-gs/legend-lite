package com.legend.warehouse.server.duck;

/** A statement DuckDB refused or failed: its message, and DuckDB's kind for it (duckdb_error_type). */
public final class DuckException extends Exception {

    private final int kind;

    DuckException(int kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** A statement stopped before it began (a cancel or a timeout while it waited). */
    public static DuckException cancelledBeforeStart() {
        return new DuckException(Duck.ERROR_INTERRUPT, "INTERRUPT Error: cancelled before start");
    }

    /** A statement DuckDB could not parse. A statement refused while it was prepared carries no kind, only DuckDB's
     *  message, which leads with it ({@code Parser Error: ...}). */
    public boolean parse() {
        return kind == Duck.ERROR_PARSER || leadsWith("Parser Error");
    }

    /** A binder or catalog error: the statement names something that is not there. */
    public boolean bind() {
        return kind == Duck.ERROR_BINDER || kind == Duck.ERROR_CATALOG
                || leadsWith("Binder Error") || leadsWith("Catalog Error");
    }

    private boolean leadsWith(String kindName) {
        return String.valueOf(getMessage()).startsWith(kindName);
    }

    public boolean interrupted() {
        return kind == Duck.ERROR_INTERRUPT;
    }
}
