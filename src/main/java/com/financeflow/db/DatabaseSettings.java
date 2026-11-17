package com.financeflow.db;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Prepara somente a configuração, para poder ser testada sem banco ou variáveis
 * reais. DATABASE_URL tem prioridade sobre as variáveis locais DB_*.
 */
public record DatabaseSettings(String jdbcUrl, String user, String password, Properties properties) {

    private static final List<String> REQUIRED_VARIABLES =
            List.of("DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME", "DB_SSLMODE");

    // Nomes da URL PostgreSQL (libpq) com nome diferente no driver JDBC.
    private static final Map<String, String> JDBC_PROPERTY_NAMES = Map.of(
            "connect_timeout", "connectTimeout",
            "channel_binding", "channelBinding",
            "application_name", "ApplicationName",
            "search_path", "currentSchema");

    public static DatabaseSettings fromEnvironment(Function<String, String> getenv) {
        String rawUrl = value(getenv, "DATABASE_URL").strip();
        if (!rawUrl.isEmpty()) {
            return fromDatabaseUrl(rawUrl);
        }
        for (String name : REQUIRED_VARIABLES) {
            if (value(getenv, name).isEmpty()) {
                throw new IllegalStateException("variavel de ambiente obrigatoria nao definida: " + name);
            }
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        // Mantém o modo local (inclusive disable) para o desenvolvimento com Docker.
        parameters.put("sslmode", getenv.apply("DB_SSLMODE"));
        parameters.put("connect_timeout", "10");
        return build(getenv.apply("DB_HOST"), getenv.apply("DB_PORT"), getenv.apply("DB_NAME"),
                getenv.apply("DB_USER"), getenv.apply("DB_PASSWORD"), parameters);
    }

    private static DatabaseSettings fromDatabaseUrl(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException exception) {
            // A mensagem original pode repetir a URL inteira, inclusive a senha.
            throw new IllegalStateException("DATABASE_URL invalida: informe uma URL PostgreSQL valida");
        }
        if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
            throw new IllegalStateException("DATABASE_URL invalida: o esquema deve ser postgres ou postgresql");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("DATABASE_URL invalida: host obrigatorio");
        }
        String rawUserInfo = uri.getRawUserInfo();
        String user = "";
        String password = null;
        if (rawUserInfo != null) {
            int separator = rawUserInfo.indexOf(':');
            user = decode(separator < 0 ? rawUserInfo : rawUserInfo.substring(0, separator), false);
            password = separator < 0 ? null : decode(rawUserInfo.substring(separator + 1), false);
        }
        if (user.isBlank()) {
            throw new IllegalStateException("DATABASE_URL invalida: usuario obrigatorio");
        }
        String database = uri.getRawPath() == null ? "" : decode(uri.getRawPath().replaceFirst("^/", ""), false);
        if (database.isBlank()) {
            throw new IllegalStateException("DATABASE_URL invalida: nome do banco obrigatorio");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "DATABASE_URL invalida: fragmentos nao sao permitidos; codifique caracteres especiais");
        }
        Map<String, String> parameters = parseQuery(uri.getRawQuery());
        // O driver aceita channel binding. Preserva a política pedida e recusa
        // valores desconhecidos em vez de enfraquecê-la silenciosamente.
        if (parameters.containsKey("channel_binding")
                && !List.of("require", "prefer", "disable").contains(parameters.get("channel_binding"))) {
            throw new IllegalStateException("DATABASE_URL: channel_binding deve ser require, prefer ou disable");
        }
        // O Neon recomenda verify-full para validar o certificado e o nome do
        // servidor, além da política de channel binding informada na URL.
        String sslMode = parameters.getOrDefault("sslmode", "");
        if (sslMode.isEmpty() || sslMode.equals("require")) {
            parameters.put("sslmode", "verify-full");
        }
        parameters.putIfAbsent("connect_timeout", "10");
        if (parameters.get("connect_timeout").isEmpty()) {
            parameters.put("connect_timeout", "10");
        }
        String port = uri.getPort() < 0 ? "5432" : Integer.toString(uri.getPort());
        return build(uri.getHost(), port, database, user, password, parameters);
    }

    private static DatabaseSettings build(String host, String port, String database, String user, String password,
                                          Map<String, String> parameters) {
        String jdbcHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String jdbcUrl = "jdbc:postgresql://" + jdbcHost + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8);
        Properties properties = new Properties();
        parameters.forEach((name, value) -> properties.setProperty(JDBC_PROPERTY_NAMES.getOrDefault(name, name), value));
        String sslMode = parameters.getOrDefault("sslmode", "");
        if ((sslMode.equals("verify-full") || sslMode.equals("verify-ca")) && !parameters.containsKey("sslrootcert")) {
            // Valida o certificado com as autoridades confiáveis da JVM, como o
            // pgx faz com as do sistema, em vez de exigir ~/.postgresql/root.crt.
            properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        }
        return new DatabaseSettings(jdbcUrl, user, password, properties);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String name = decode(separator < 0 ? pair : pair.substring(0, separator), true);
            String value = separator < 0 ? "" : decode(pair.substring(separator + 1), true);
            parameters.putIfAbsent(name, value);
        }
        return parameters;
    }

    private static String decode(String value, boolean query) {
        try {
            // Fora da query, '+' é literal; URLDecoder o transformaria em espaço.
            return URLDecoder.decode(query ? value : value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DATABASE_URL invalida: parametros de conexao invalidos");
        }
    }

    private static String value(Function<String, String> getenv, String name) {
        String value = getenv.apply(name);
        return value == null ? "" : value;
    }
}
