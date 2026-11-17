package com.financeflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.financeflow.db.DatabaseSettings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Banco descartável para os testes de integração. Cada uso cria um schema próprio
 * e o remove ao final; nunca usa o banco do usuário.
 */
public final class TestDatabase implements AutoCloseable {

    public final String dsn;
    public final String schema;
    public final DataSource dataSource;
    private final DataSource admin;

    private TestDatabase(String dsn) {
        this.dsn = dsn;
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(Map.of("DATABASE_URL", dsn)::get);
        assertThat(settings.jdbcUrl().substring(settings.jdbcUrl().lastIndexOf('/') + 1))
                .as("o teste exige um banco descartável financeflow_test_*").startsWith("financeflow_test_");
        this.admin = dataSource(settings, new Properties());
        // Identificadores hexadecimais gerados não escapam do schema de teste.
        byte[] suffix = new byte[8];
        ThreadLocalRandom.current().nextBytes(suffix);
        this.schema = "financeflow_it_" + HexFormat.of().formatHex(suffix);
        execute(admin, "CREATE SCHEMA " + schema);
        Properties scoped = new Properties();
        scoped.setProperty("currentSchema", schema);
        this.dataSource = dataSource(settings, scoped);
    }

    public static TestDatabase open() {
        String dsn = System.getenv("FINANCEFLOW_TEST_DSN");
        assumeTrue(dsn != null && !dsn.isBlank(), "defina FINANCEFLOW_TEST_DSN para um banco descartável financeflow_test_*");
        return new TestDatabase(dsn);
    }

    /** DATABASE_URL que a aplicação usa para enxergar somente o schema deste teste. */
    public String applicationUrl() {
        return dsn + (dsn.contains("?") ? "&" : "?") + "search_path=" + schema;
    }

    public static void execute(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DataSource dataSource(DatabaseSettings settings, Properties extra) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(settings.jdbcUrl());
        Properties properties = new Properties();
        properties.putAll(settings.properties());
        properties.putAll(extra);
        properties.setProperty("user", settings.user());
        if (settings.password() != null) {
            properties.setProperty("password", settings.password());
        }
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    @Override
    public void close() {
        execute(admin, "DROP SCHEMA " + schema + " CASCADE");
    }
}
