package com.studyflow.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real, hermetic Postgres for integration tests — replaces the local `studyflow_test` deviation
 * (see docs/DECISIONS.md's original "Testcontainers -> local Postgres" entry, now superseded)
 * now that Docker is available. One container for the whole JVM/surefire-fork, not one per test
 * class: starting a fresh Postgres per class would make a 15+ class suite unusably slow. The
 * `pgvector/pgvector:pg15` image ships the extension's binaries; `V6.1__pgvector_extension_early
 * .sql` is what actually runs `CREATE EXTENSION` (Flyway, once Spring boots against this
 * container) — nothing extra needed here.
 *
 * <p>Auto-registered as a JUnit5 global extension (see {@code junit-platform.properties} and
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}) purely so this class gets
 * loaded — and its static initializer runs — before any {@code @SpringBootTest} context is
 * created, without editing every existing integration test file to extend a base class.
 *
 * <p>{@code src/test/resources/application.yml}'s datasource block reads {@code ${DB_URL}}/
 * {@code ${DB_USER}}/{@code ${DB_PASSWORD}}, same as the main config — the {@code
 * System.setProperty} calls below win over the real dev-DB values in {@code .env} because JVM
 * system properties outrank OS environment variables in Spring's property source order.
 */
public class TestcontainersPostgresExtension implements BeforeAllCallback {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("studyflow_test")
                .withUsername("studyflow")
                .withPassword("studyflow");
        POSTGRES.start();
        System.setProperty("DB_URL", POSTGRES.getJdbcUrl());
        System.setProperty("DB_USER", POSTGRES.getUsername());
        System.setProperty("DB_PASSWORD", POSTGRES.getPassword());
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // No-op — the static initializer above already did the work, once, the first time this
        // class was loaded (which JUnit5's global-extension auto-detection guarantees happens
        // before the first test's Spring context is created).
    }
}
