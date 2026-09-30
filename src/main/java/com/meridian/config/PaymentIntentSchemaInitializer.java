package com.meridian.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class PaymentIntentSchemaInitializer implements InitializingBean {
    private final DataSource dataSource;
    private final String jdbcUrl;

    public PaymentIntentSchemaInitializer(DataSource dataSource,
                                          @Value("${spring.datasource.url:}") String jdbcUrl) {
        this.dataSource = dataSource;
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public void afterPropertiesSet() {
        PaymentIntentMigrations.migrate(dataSource, jdbcUrl);
    }
}
