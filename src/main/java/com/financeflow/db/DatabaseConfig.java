package com.financeflow.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DatabaseConfig {

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(Environment environment) {
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(environment::getProperty);
        HikariConfig config = new HikariConfig();
        config.setPoolName("financeflow");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setDataSourceProperties(settings.properties());
        // Um pool por instância do servidor. O pooler do provedor ainda controla
        // o total de conexões ao banco entre as instâncias.
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setIdleTimeout(Duration.ofMinutes(5).toMillis());
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        // A primeira conexão acontece na migração, que interrompe a inicialização
        // com uma mensagem sem dados da conexão.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    public DatabaseMigrator databaseMigrator(DataSource dataSource,
                                             @Value("${financeflow.database.migrate:true}") boolean migrate) {
        return new DatabaseMigrator(dataSource, migrate);
    }
}
