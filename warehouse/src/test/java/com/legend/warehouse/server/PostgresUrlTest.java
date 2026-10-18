package com.legend.warehouse.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Postgres catalog written as {@code psql} takes a database, {@code postgresql://bob@db:5432/shop}
 * (docs/DATACUBE_APP_PLAN_2026_10_02.md, A1): read into the libpq connection string it attaches with. Moved out of
 * AppModeTest with the dialect program's Phase 7 (2026-10-18): the app is any warehouse's, the URL is Postgres's.
 */
class PostgresUrlTest {

    @Test
    void aPostgresUrlIsACatalogNamedAfterItsDatabase() {
        PostgresUrl u = PostgresUrl.parse("postgresql://bob@db.internal:5433/shop");
        assertEquals("shop", u.catalog());
        assertEquals("host='db.internal' port='5433' dbname='shop' user='bob' options='-c statement_timeout=60000'",
                u.dsn());
        // no host: libpq's own default (the local socket); parameters as given
        assertEquals("dbname='shop' sslmode='require' options='-c statement_timeout=60000'",
                PostgresUrl.parse("postgres:///shop?sslmode=require").dsn());
        // a percent-encoded user and password, decoded and quoted
        assertEquals("host='db' dbname='shop' user='bo b' password='p@ss:w\\'d' options='-c statement_timeout=60000'",
                PostgresUrl.parse("postgresql://bo%20b:p%40ss%3Aw'd@db/shop").dsn());
        // the URL's own options replace the default timeout
        assertEquals("host='db' dbname='shop' options='-c statement_timeout=5000'",
                PostgresUrl.parse("postgresql://db/shop?options=-c%20statement_timeout%3D5000").dsn());
        assertEquals("host='::1' dbname='shop' options='-c statement_timeout=60000'",
                PostgresUrl.parse("postgresql://[::1]/shop").dsn());
    }

    @Test
    void aPostgresUrlThatCannotBeACatalogIsRefusedByName() {
        for (String bad : List.of(
                "postgresql://db",                  // no database
                "postgresql://db/",
                "postgresql://db/Shop",             // not a catalog name: --postgres NAME=DSN
                "postgresql://db/shop/extra",
                "postgresql://east,west/shop",      // several hosts
                "postgresql://db/shop?sslmode",     // not key=value
                "postgresql://db/shop?dbname=x")) { // given twice
            assertThrows(IllegalArgumentException.class, () -> PostgresUrl.parse(bad), bad);
        }
    }

    @Test
    void anAskedPasswordIsQuotedIntoTheConnectionString() {
        assertEquals("host='db' dbname='shop' password='it\\'s \\\\ fine'",
                PostgresUrl.withPassword("host='db' dbname='shop'", "it's \\ fine".toCharArray()));
    }
}
