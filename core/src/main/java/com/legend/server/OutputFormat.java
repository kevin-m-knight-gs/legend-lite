package com.legend.server;

/**
 * The text {@link QueryService#execute(String, String, String, java.sql.Connection, java.io.OutputStream,
 * OutputFormat)} writes a query's typed rows in: lite's CSV ({@link com.legend.result.CsvWriter}) or its JSON rows
 * ({@link com.legend.result.JsonRowsWriter}), each written batch by batch as the rows arrive. Each constant carries its
 * stable identifier ({@link #id()}) and the MIME type a server sends with it ({@link #contentType()}).
 */
public enum OutputFormat {
    JSON("json", "application/json"),
    CSV("csv", "text/csv");

    private final String id;
    private final String contentType;

    OutputFormat(String id, String contentType) {
        this.id = id;
        this.contentType = contentType;
    }

    public String id() {
        return id;
    }

    public String contentType() {
        return contentType;
    }
}
