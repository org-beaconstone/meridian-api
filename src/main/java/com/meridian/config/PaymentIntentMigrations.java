package com.meridian.config;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Applies {@code db/migration} only when the configured datasource is PostgreSQL. */
public final class PaymentIntentMigrations {
    static final String LOCATION = "classpath:db/migration";

    private PaymentIntentMigrations() {}

    public static boolean appliesTo(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    public static void migrate(DataSource dataSource, String jdbcUrl) {
        if (!appliesTo(jdbcUrl)) {
            return;
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .load()
                .migrate();
    }
}
