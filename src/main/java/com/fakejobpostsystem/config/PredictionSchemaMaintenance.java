package com.fakejobpostsystem.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PredictionSchemaMaintenance {

    private final JdbcTemplate jdbcTemplate;

    public PredictionSchemaMaintenance(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void maintainLightweightSchemaDefaults() {
        try {
            jdbcTemplate.execute("ALTER TABLE predictions ALTER COLUMN user_id DROP NOT NULL");
        } catch (Exception ex) {
            System.err.println("Could not relax predictions.user_id nullability: " + ex.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(40) DEFAULT 'ROLE_USER'");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS institution_id BIGINT");
            jdbcTemplate.execute("UPDATE users SET role = 'ROLE_USER' WHERE role IS NULL OR role = ''");
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN role SET NOT NULL");
        } catch (Exception ex) {
            System.err.println("Could not maintain user.role default: " + ex.getMessage());
        }
    }
}
