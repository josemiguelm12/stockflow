package com.stockflow.identity;

import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T04-H02: con DEBUG activo (como con {@code DEBUG=...} o {@code debug=true}), Spring registra los cuerpos leídos y
 * escritos, las consultas SQL y la autenticación. Ninguno de esos registros puede contener un email ni una contraseña.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "debug=true",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.security=DEBUG",
        "logging.level.org.springframework.jdbc.core=DEBUG"
})
class SensitiveDataDebugLoggingIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String ADMIN = "debug-admin@example.test";
    private static final String USER = "debug-user@example.test";
    private static final String FRESH = "debug-fresh@example.test";
    private static final String UNKNOWN = "debug-nobody@example.test";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    private UUID createUser(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now(), now())
                """, id, email, bcrypt.encode(PASSWORD), role);
        return id;
    }

    private MvcResult json(MockHttpServletRequestBuilder request, String body, String bearer) throws Exception {
        request.contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }

    private String login(String email) throws Exception {
        MvcResult result = json(post("/api/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).as("login " + result.getResponse().getStatus()).isTrue();
        return m.group(1);
    }

    @Test
    void noEmailOrPasswordReachesTheLogsWithDebugEnabled(CapturedOutput output) throws Exception {
        createUser(ADMIN, "ADMIN");
        UUID user = createUser(USER, "STANDARD");
        String admin = login(ADMIN);
        String standard = login(USER);

        // Flujos públicos con email en el cuerpo de la petición o de la respuesta.
        json(post("/api/v1/auth/register"), "{\"email\":\"" + FRESH + "\",\"password\":\"" + PASSWORD + "\"}", null);
        json(post("/api/v1/auth/resend-activation"), "{\"email\":\"" + FRESH + "\"}", null);
        json(post("/api/v1/auth/password/forgot"), "{\"email\":\"" + USER + "\"}", null);
        json(post("/api/v1/auth/password/forgot"), "{\"email\":\"" + UNKNOWN + "\"}", null);
        json(post("/api/v1/auth/login"), "{\"email\":\"" + UNKNOWN + "\",\"password\":\"" + PASSWORD + "\"}", null);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + standard));

        // Administración: listado (emails en la respuesta), cambios y reset forzado (con y sin cuerpo).
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + admin));
        json(patch("/api/v1/admin/users/" + user + "/role"), "{\"role\":\"ADMIN\"}", admin);
        json(patch("/api/v1/admin/users/" + user + "/role"), "{\"role\":\"STANDARD\",\"email\":\"" + USER + "\"}", admin);
        json(post("/api/v1/admin/users/" + user + "/force-password-reset"), "{\"email\":\"" + USER + "\"}", admin);
        mvc.perform(post("/api/v1/admin/users/" + user + "/force-password-reset").header("Authorization", "Bearer " + admin));

        String logs = output.getAll();
        assertThat(logs).as("DEBUG debe estar realmente activo").containsAnyOf("Read \"application/json", "Writing [");
        for (String email : new String[]{ADMIN, USER, FRESH, UNKNOWN}) {
            assertThat(logs).as(email).doesNotContain(email);
        }
        assertThat(logs).doesNotContain(PASSWORD).doesNotContain(admin).doesNotContain(standard);
    }
}
