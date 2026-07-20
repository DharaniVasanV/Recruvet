package com.fakejobpostsystem.config;

import java.net.URI;
import java.net.URISyntaxException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(
            @Value("${DATABASE_URL:}") String databaseUrl,
            @Value("${DB_USERNAME:}") String configuredUsername,
            @Value("${DB_PASSWORD:}") String configuredPassword) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");

        if (databaseUrl == null || databaseUrl.isBlank()) {
            dataSource.setUrl("jdbc:postgresql://localhost:5432/fakejobpostsystem");
            dataSource.setUsername(configuredUsername.isBlank() ? "postgres" : configuredUsername);
            dataSource.setPassword(configuredPassword);
            return dataSource;
        }

        if (databaseUrl.startsWith("jdbc:postgresql://")) {
            dataSource.setUrl(databaseUrl);
            dataSource.setUsername(configuredUsername);
            dataSource.setPassword(configuredPassword);
            return dataSource;
        }

        try {
            String normalizedUrl = databaseUrl
                    .replaceFirst("^postgres://", "http://")
                    .replaceFirst("^postgresql://", "http://");
            URI uri = new URI(normalizedUrl);

            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost()
                    + ":" + port
                    + uri.getPath();

            String username = configuredUsername;
            String password = configuredPassword;
            if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
                String[] parts = uri.getUserInfo().split(":", 2);
                username = parts[0];
                password = parts[1];
            }

            if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                jdbcUrl += "?" + uri.getQuery();
            }

            dataSource.setUrl(jdbcUrl);
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            return dataSource;
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Invalid DATABASE_URL format", ex);
        }
    }
}
