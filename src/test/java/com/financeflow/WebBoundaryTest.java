package com.financeflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Fronteira HTTP completa (filtros, rotas e erros) sem acesso a um banco real. */
class WebBoundaryTest {

    static final String OFFLINE_DATABASE = "DATABASE_URL=postgres://test@127.0.0.1:1/financeflow_offline?sslmode=disable";
    static final String NO_MIGRATION = "financeflow.database.migrate=false";

    static MockHttpServletResponse send(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    @Nested
    @SpringBootTest(properties = {OFFLINE_DATABASE, NO_MIGRATION, "GOOGLE_CLIENT_ID=test-client",
            "GOOGLE_CLIENT_SECRET=secret-not-for-the-browser",
            "GOOGLE_REDIRECT_URL=https://finance.example/auth/google/callback"})
    @AutoConfigureMockMvc
    class GoogleEnabled {

        @Autowired
        MockMvc mvc;

        @Test
        void googleHttpBoundary() throws Exception {
            MockHttpServletResponse providers = send(mvc, get("/auth/providers").header("Host", "finance.example"));
            assertThat(providers.getStatus()).isEqualTo(200);
            assertThat(providers.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"google\":true}")
                    .doesNotContain("secret-not-for-the-browser");

            MockHttpServletResponse root = send(mvc, get("/").header("Host", "old-alias.example"));
            assertThat(root.getStatus()).isEqualTo(307);
            assertThat(root.getHeader("Location")).isEqualTo("https://finance.example/");

            MockHttpServletResponse start = send(mvc, post("/auth/google/start").header("Host", "old-alias.example"));
            assertThat(start.getStatus()).isEqualTo(303);
            assertThat(start.getHeader("Location")).isEqualTo("https://finance.example/");

            assertThat(send(mvc, post("/auth/google/link/start").header("Host", "finance.example")).getStatus())
                    .isEqualTo(401);
            assertThat(send(mvc, post("/auth/google/start").header("Host", "finance.example")
                    .header("Sec-Fetch-Site", "cross-site")).getStatus()).isEqualTo(403);

            MockHttpServletResponse callback = send(mvc, get("/auth/google/callback")
                    .param("state", "private-state").param("code", "private-code").header("Host", "finance.example"));
            assertThat(callback.getStatus()).isEqualTo(303);
            assertThat(callback.getHeader("Referrer-Policy")).isEqualTo("no-referrer");

            MockHttpServletResponse asset = send(mvc, get("/static/google-g.png").header("Host", "finance.example"));
            assertThat(asset.getStatus()).isEqualTo(200);
            assertThat(asset.getContentType()).isEqualTo("image/png");
            assertThat(asset.getHeader("Content-Security-Policy"))
                    .contains("form-action 'self' https://accounts.google.com");
        }
    }

    @Nested
    @SpringBootTest(properties = {OFFLINE_DATABASE, NO_MIGRATION, "GOOGLE_CLIENT_ID=", "GOOGLE_CLIENT_SECRET=",
            "GOOGLE_REDIRECT_URL="})
    @AutoConfigureMockMvc
    class GoogleDisabled {

        @Autowired
        MockMvc mvc;

        @Test
        void passwordAuthRemainsAvailable() throws Exception {
            assertThat(send(mvc, get("/")).getContentAsString(StandardCharsets.UTF_8)).contains("auth-form");
            assertThat(send(mvc, get("/auth/providers")).getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"google\":false}");
            MockHttpServletResponse me = send(mvc, get("/auth/me"));
            assertThat(me.getStatus()).isEqualTo(401);
            assertThat(me.getContentAsString(StandardCharsets.UTF_8)).contains("message");
        }

        @Test
        void dashboardAssetsAreServed() throws Exception {
            String[][] assets = {
                    {"/", "text/html", "/static/dashboard.js"},
                    {"/static/dashboard.js", "text/javascript", "FinanceDashboard"},
                    {"/static/app.js", "text/javascript", "renderDashboard"},
                    {"/static/style.css", "text/css", "{"},
            };
            for (String[] asset : assets) {
                MockHttpServletResponse response = send(mvc, get(asset[0]));
                assertThat(response.getStatus()).as(asset[0]).isEqualTo(200);
                assertThat(response.getContentType()).as(asset[0]).contains(asset[1]);
                assertThat(response.getContentAsString(StandardCharsets.UTF_8)).as(asset[0]).contains(asset[2]);
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            }
        }

        @Test
        void protectedRoutesAndCrossOriginWrites() throws Exception {
            for (String path : new String[] {"/transactions", "/categories", "/auth/me", "/transactions/1"}) {
                assertThat(send(mvc, get(path)).getStatus()).as(path).isEqualTo(401);
            }
            MockHttpServletResponse blocked = send(mvc, post("/categories").header("Host", "localhost:8081")
                    .header("Origin", "https://another-site.invalid").contentType("application/json")
                    .content("{\"name\":\"Cross site\"}"));
            assertThat(blocked.getStatus()).isEqualTo(403);
            assertThat(blocked.getContentAsString(StandardCharsets.UTF_8)).contains("Origem da requisição não permitida.");
            // Mesmo host, sem sessão: passa pela proteção de origem e chega à autenticação.
            assertThat(send(mvc, post("/categories").header("Host", "localhost:8081")
                    .header("Origin", "http://localhost:8081").contentType("application/json")
                    .content("{\"name\":\"Same site\"}")).getStatus()).isEqualTo(401);
        }

        @Test
        void invalidJsonUsesSpecificMessages() throws Exception {
            MockHttpServletResponse register = send(mvc, post("/auth/register").contentType("application/json")
                    .content("{not json"));
            assertThat(register.getStatus()).isEqualTo(400);
            assertThat(register.getContentAsString(StandardCharsets.UTF_8)).contains("Envie nome, e-mail e senha em JSON valido.");
            MockHttpServletResponse login = send(mvc, post("/auth/login").contentType("application/json")
                    .content("[]"));
            assertThat(login.getStatus()).isEqualTo(400);
            assertThat(login.getContentAsString(StandardCharsets.UTF_8)).contains("Envie e-mail e senha em JSON valido.");
            MockHttpServletResponse invalid = send(mvc, post("/auth/register").contentType("application/json")
                    .content("{\"name\":\"A\",\"email\":\"invalid\",\"password\":\"short\"}"));
            assertThat(invalid.getStatus()).isEqualTo(400);
            assertThat(invalid.getContentAsString(StandardCharsets.UTF_8)).contains("dados inválidos: informe um e-mail valido");
            MockHttpServletResponse large = send(mvc, post("/auth/login").contentType("application/json")
                    .content("{\"email\":\"" + "a".repeat(1 << 20) + "\"}"));
            assertThat(large.getStatus()).isEqualTo(400);
        }
    }
}
