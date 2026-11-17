package com.financeflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeflow.db.DatabaseMigrator;
import com.financeflow.usecase.Tokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** O banco deve ser descartável. Executa a aplicação completa sobre um schema próprio. */
class UserIsolationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Cliente com os próprios cookies, como um navegador. */
    final class Browser {
        final Map<String, String> cookies = new HashMap<>();

        HttpResponse<String> call(String method, String path, Object input, int status) throws Exception {
            return call(method, path, input, status, Map.of());
        }

        HttpResponse<String> call(String method, String path, Object input, int status, Map<String, String> headers)
                throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
            if (input != null) {
                request.header("Content-Type", "application/json");
            }
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ",
                        cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            headers.forEach(request::header);
            request.method(method, input == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input)));
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                String name = pair.substring(0, pair.indexOf('='));
                String value = pair.substring(pair.indexOf('=') + 1);
                if (value.isEmpty() || header.contains("Max-Age=0")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
            assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isEqualTo(status);
            return response;
        }
    }

    private String base;

    @Test
    void userIsolationFlow() throws Exception {
        try (TestDatabase database = TestDatabase.open()) {
            JdbcClient sql = JdbcClient.create(database.dataSource);
            sql.sql("""
                    CREATE TABLE transaction (id SERIAL PRIMARY KEY, description VARCHAR(200) NOT NULL,
                        amount NUMERIC(10,2) NOT NULL, type VARCHAR(15) NOT NULL, category VARCHAR(15) NOT NULL,
                        date DATE NOT NULL)""").update();
            sql.sql("INSERT INTO transaction(description,amount,type,category,date) "
                    + "VALUES ('Registro legado de teste',10,'expense','food','2026-10-01')").update();
            DatabaseMigrator.migrate(database.dataSource);
            DatabaseMigrator.migrate(database.dataSource);

            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(FinanceFlowApplication.class)
                    .properties(Map.of("DATABASE_URL", database.applicationUrl(), "server.port", "0",
                            "GOOGLE_CLIENT_ID", "", "GOOGLE_CLIENT_SECRET", "", "GOOGLE_REDIRECT_URL", "",
                            "SESSION_COOKIE_SECURE", "false"))
                    .run()) {
                base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
                run(sql);
            }
        }
    }

    private void run(JdbcClient sql) throws Exception {
        Browser a = new Browser();
        Browser b = new Browser();
        Browser anonymous = new Browser();
        for (String path : List.of("/transactions", "/categories", "/auth/me")) {
            anonymous.call("GET", path, null, 401);
        }
        anonymous.call("POST", "/auth/register", Map.of("name", "A", "email", "invalid", "password", "short"), 400);
        String password = "Local-test-password9!";
        JsonNode userA = register(a, " User-A@financeflow.test ", password);
        JsonNode userB = register(b, "user-b@financeflow.test", password);
        anonymous.call("POST", "/auth/register",
                Map.of("name", "Duplicado", "email", "USER-A@financeflow.test", "password", password), 409);
        anonymous.call("POST", "/auth/login",
                Map.of("email", "user-a@financeflow.test", "password", "incorrect-password"), 401);
        a.call("GET", "/auth/me", null, 200);
        assertThat(a.call("GET", "/transactions", null, 200).body()).isEqualTo("[]");

        int categoryA = JSON.readTree(a.call("POST", "/categories", Map.of("name", "Academia"), 201).body())
                .get("id").asInt();
        assertThat(b.call("GET", "/categories", null, 200).body()).isEqualTo("[]");
        a.call("POST", "/categories", Map.of("name", "academia"), 409);
        b.call("POST", "/categories", Map.of("name", "Academia"), 201);

        Map<String, Object> input = Map.of("description", "Mensalidade", "amount", 99.90, "type", "expense",
                "category_id", categoryA, "date", "2026-10-28", "user_id", 999999);
        JsonNode transaction = JSON.readTree(a.call("POST", "/transactions", input, 201).body());
        assertThat(transaction.get("category").asText()).isEqualTo("Academia");
        assertThat(transaction.get("amount").decimalValue()).isEqualByComparingTo("99.90");
        int transactionId = transaction.get("id").asInt();
        String path = "/transactions/" + transactionId;
        a.call("GET", path, null, 200);
        b.call("GET", path, null, 404);
        b.call("DELETE", path, null, 404);
        b.call("POST", "/transactions", input, 400);
        a.call("GET", "/transactions/abc", null, 400);
        assertThat(b.call("GET", "/transactions", null, 200).body()).isEqualTo("[]");

        int owner = sql.sql("SELECT user_id FROM transaction WHERE id = ?").param(transactionId)
                .query(Integer.class).single();
        assertThat(owner).as("proprietário foi aceito do payload").isEqualTo(userA.get("id").asInt());
        // A restrição do banco também impede categoria de outra pessoa.
        assertThatThrownBy(() -> sql.sql("INSERT INTO transaction(description,amount,type,category,date,user_id,"
                + "category_id) VALUES ('invalid',1,'expense','Academia','2026-10-28',?,?)")
                .params(userB.get("id").asInt(), categoryA).update()).isNotNull();

        // Escritas de outro site são recusadas, mesmo com sessão.
        a.call("POST", "/categories", Map.of("name", "Cross site"), 403,
                Map.of("Origin", "https://another-site.invalid"));

        String passwordHash = sql.sql("SELECT password_hash FROM users WHERE id = ?").param(owner)
                .query(String.class).single();
        assertThat(passwordHash).isNotEqualTo(password);
        assertThat(new BCryptPasswordEncoder().matches(password, passwordHash)).isTrue();
        String tokenHash = Tokens.hash(a.cookies.get("financeflow_session"));
        assertThat(sql.sql("SELECT count(*) FROM sessions WHERE token_hash = ?").param(tokenHash)
                .query(Integer.class).single()).isEqualTo(1);

        a.call("DELETE", path, null, 204);
        a.call("GET", path, null, 404);
        a.call("POST", "/auth/logout", null, 204);
        a.call("GET", "/auth/me", null, 401);
        assertThat(sql.sql("SELECT count(*) FROM sessions WHERE token_hash = ?").param(tokenHash)
                .query(Integer.class).single()).as("logout não revogou sessão").isZero();

        a.call("POST", "/auth/login", Map.of("email", "user-a@financeflow.test", "password", password), 200);
        a.call("GET", "/categories", null, 200);
        sql.sql("UPDATE sessions SET expires_at = NOW() - INTERVAL '1 minute' WHERE user_id = ?").param(owner).update();
        a.call("GET", "/auth/me", null, 401);
        assertThat(sql.sql("SELECT count(*) FROM transaction WHERE user_id IS NULL "
                + "AND description = 'Registro legado de teste'").query(Integer.class).single())
                .as("registro legado não foi preservado").isEqualTo(1);
    }

    private JsonNode register(Browser browser, String email, String password) throws Exception {
        HttpResponse<String> response = browser.call("POST", "/auth/register",
                Map.of("name", "Pessoa Teste", "email", email, "password", password), 201);
        assertThat(response.body()).doesNotContain("password");
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).contains("HttpOnly", "SameSite=Strict");
        return JSON.readTree(response.body()).get("user");
    }
}
