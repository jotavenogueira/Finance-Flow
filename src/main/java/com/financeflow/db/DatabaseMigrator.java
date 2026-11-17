package com.financeflow.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Executa cada versão uma única vez. A transação garante que uma falha não deixe
 * o banco parcialmente alterado; o lock serializa inicializações simultâneas.
 */
public class DatabaseMigrator implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseMigrator.class);

    private final DataSource dataSource;
    private final boolean enabled;

    public DatabaseMigrator(DataSource dataSource, boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (enabled) {
            migrate(dataSource);
        }
    }

    public static void migrate(DataSource dataSource) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException exception) {
            // Erros do driver podem conter valores da conexão; não são repassados.
            throw new IllegalStateException(
                    "conectar ao PostgreSQL: verifique a configuracao e a disponibilidade do banco");
        }
        try (connection) {
            connection.setAutoCommit(false);
            try {
                apply(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("aplicar migrações: " + exception.getMessage(), exception);
        }
        LOG.info("Successfully connected to the database!");
    }

    private static void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute("SELECT pg_advisory_xact_lock(61437821)");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations "
                    + "(version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
        }
        // Os arquivos usam prefixo numérico com zeros à esquerda, então a ordem
        // lexical também executa as versões mais novas depois das anteriores.
        Resource[] scripts = migrationScripts();
        Arrays.sort(scripts, Comparator.comparing(Resource::getFilename));
        Set<Integer> seen = new HashSet<>();
        for (Resource script : scripts) {
            String name = script.getFilename();
            int version = parseVersion(name);
            if (version < 1 || !seen.add(version)) {
                throw new IllegalStateException("nome de migração inválido ou repetido: " + name);
            }
            if (applied(connection, version)) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);
                statement.execute(read(script));
            } catch (SQLException exception) {
                throw new SQLException("aplicar migração " + version + ": " + exception.getMessage(), exception);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migrations(version) VALUES (?)")) {
                insert.setInt(1, version);
                insert.executeUpdate();
            }
        }
    }

    private static boolean applied(Connection connection, int version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = ?)")) {
            query.setInt(1, version);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static int parseVersion(String name) {
        int separator = name.indexOf('_');
        if (separator < 1) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(0, separator));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Resource[] migrationScripts() {
        try {
            return new PathMatchingResourcePatternResolver().getResources("classpath:db/migrations/*.sql");
        } catch (IOException exception) {
            throw new IllegalStateException("ler migrações", exception);
        }
    }

    private static String read(Resource script) {
        try (InputStream input = script.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("ler migração " + script.getFilename(), exception);
        }
    }
}
