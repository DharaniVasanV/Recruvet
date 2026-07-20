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
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS share_with_institution BOOLEAN DEFAULT FALSE");
            jdbcTemplate.execute("UPDATE users SET share_with_institution = FALSE WHERE share_with_institution IS NULL");
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN share_with_institution SET DEFAULT FALSE");
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN share_with_institution SET NOT NULL");
            jdbcTemplate.execute("UPDATE users SET role = 'ROLE_USER' WHERE role IS NULL OR role = ''");
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN role SET NOT NULL");
        } catch (Exception ex) {
            System.err.println("Could not maintain user.role default: " + ex.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE predictions ADD COLUMN IF NOT EXISTS institution_id BIGINT");
            jdbcTemplate.execute("ALTER TABLE predictions ADD COLUMN IF NOT EXISTS shared_with_institution BOOLEAN DEFAULT FALSE");
            jdbcTemplate.execute("UPDATE predictions SET shared_with_institution = FALSE WHERE shared_with_institution IS NULL");
            jdbcTemplate.execute("ALTER TABLE predictions ALTER COLUMN shared_with_institution SET DEFAULT FALSE");
            jdbcTemplate.execute("ALTER TABLE predictions ALTER COLUMN shared_with_institution SET NOT NULL");
        } catch (Exception ex) {
            System.err.println("Could not maintain prediction sharing defaults: " + ex.getMessage());
        }
    }
}
