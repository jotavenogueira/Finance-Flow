package com.financeflow.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseSettingsTest {

    private static Map<String, String> localConfiguration() {
        return new HashMap<>(Map.of("DB_HOST", "127.0.0.1", "DB_PORT", "5433", "DB_USER", "local_user",
                "DB_PASSWORD", "synthetic-password", "DB_NAME", "local_db", "DB_SSLMODE", "disable"));
    }

    private static DatabaseSettings build(Map<String, String> values) {
        return DatabaseSettings.fromEnvironment(values::get);
    }

    private static DatabaseSettings fromUrl(String url) {
        return build(Map.of("DATABASE_URL", url));
    }

    @Test
    void databaseUrlTakesPrecedence() {
        Map<String, String> values = localConfiguration();
        values.put("DATABASE_URL", "postgresql://cloud_user:cloud_password@db.example.test/cloud_db");
        DatabaseSettings settings = build(values);
        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.test:5432/cloud_db");
        assertThat(settings.user()).isEqualTo("cloud_user");
        assertThat(settings.password()).isEqualTo("cloud_password");
        // Uma URL completa também funciona sem nenhuma das seis variáveis locais.
        fromUrl(values.get("DATABASE_URL"));
    }

    @Test
    void rejectsInvalidUrlWithoutLeakingValues() {
        String secret = "never-print-this-password";
        List<String> urls = List.of(
                "https://user:" + secret + "@db.example.test/database",
                "postgres://user:" + secret + "%zz@db.example.test/database",
                "postgres://user:" + secret + "@db.example.test:invalid/database",
                "postgres://user:" + secret + "@/database",
                "postgres://db.example.test/database?password=" + secret,
                "postgres://:" + secret + "@db.example.test/database",
                "postgres://user:" + secret + "@db.example.test",
                "postgres://user:" + secret + "@db.example.test/",
                "postgres://user:" + secret + "@db.example.test/database#unexpected",
                "postgres://user:" + secret + "@db.example.test/database?application_name=%zz");
        for (String url : urls) {
            Map<String, String> values = localConfiguration();
            values.put("DATABASE_URL", url);
            assertThatThrownBy(() -> build(values)).as(url).isInstanceOfSatisfying(IllegalStateException.class,
                    exception -> assertThat(exception.getMessage()).doesNotContain(secret, url, "db.example.test"));
        }
    }

    @Test
    void localValuesAreEscapedAndTlsModePreserved() {
        Map<String, String> values = localConfiguration();
        values.put("DB_HOST", "::1");
        values.put("DB_USER", "user @:/?&%");
        values.put("DB_PASSWORD", "password @:/?&#%+='");
        values.put("DB_NAME", "database /?&#%");
        DatabaseSettings settings = build(values);
        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://[::1]:5433/"
                + URLEncoder.encode("database /?&#%", StandardCharsets.UTF_8));
        assertThat(settings.user()).isEqualTo(values.get("DB_USER"));
        assertThat(settings.password()).isEqualTo(values.get("DB_PASSWORD"));
        assertThat(settings.properties()).containsEntry("sslmode", "disable").containsEntry("connectTimeout", "10")
                .doesNotContainKey("sslfactory");
    }

    @Test
    void requiresEveryLocalVariableWithoutUrl() {
        for (String name : List.of("DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME", "DB_SSLMODE")) {
            Map<String, String> values = localConfiguration();
            values.remove(name);
            assertThatThrownBy(() -> build(values)).hasMessageContaining(name);
        }
    }

    @Test
    void databaseUrlTlsAndTimeout() {
        String[][] cases = {
                {"", "verify-full", "10"},
                {"?sslmode=require", "verify-full", "10"},
                {"?sslmode=", "verify-full", "10"},
                {"?sslmode=verify-full", "verify-full", "10"},
                {"?sslmode=verify-ca", "verify-ca", "10"},
                {"?sslmode=disable", "disable", "10"},
                {"?connect_timeout=23", "verify-full", "23"},
        };
        for (String[] sample : cases) {
            DatabaseSettings settings = fromUrl("postgres://user:password@db.example.test/database" + sample[0]);
            assertThat(settings.properties()).as(sample[0]).containsEntry("sslmode", sample[1])
                    .containsEntry("connectTimeout", sample[2]);
        }
        assertThat(fromUrl("postgres://user:password@db.example.test/database").properties())
                .containsEntry("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
    }

    @Test
    void preservesAdditionalUrlParametersAndEscapes() {
        DatabaseSettings settings = fromUrl("postgresql://user%40name:password%40%3A%2F%3F%26%23%25@db.example.test:5432/"
                + "database%20space?sslmode=require&connect_timeout=20&application_name=FinanceFlow+dashboard"
                + "&options=-c+statement_timeout%3D5000&search_path=custom");
        assertThat(settings.user()).isEqualTo("user@name");
        assertThat(settings.password()).isEqualTo("password@:/?&#%");
        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.test:5432/database+space");
        assertThat(settings.properties()).containsEntry("ApplicationName", "FinanceFlow dashboard")
                .containsEntry("options", "-c statement_timeout=5000").containsEntry("connectTimeout", "20")
                .containsEntry("currentSchema", "custom");
    }

    @Test
    void preservesChannelBindingPolicy() {
        for (String policy : List.of("require", "prefer", "disable")) {
            DatabaseSettings settings = fromUrl(
                    "postgres://user:password@db.example.test/database?channel_binding=" + policy);
            assertThat(settings.properties()).containsEntry("channelBinding", policy)
                    .containsEntry("sslmode", "verify-full");
        }
    }

    @Test
    void rejectsInvalidChannelBindingPolicy() {
        for (String policy : List.of("", "unknown", "REQUIRE")) {
            assertThatThrownBy(() -> fromUrl(
                    "postgres://user:password@db.example.test/database?channel_binding=" + policy))
                    .hasMessageContaining("channel_binding");
        }
    }
}
