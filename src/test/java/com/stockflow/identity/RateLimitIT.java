package com.stockflow.identity;

import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T02-08 de extremo a extremo: límite de login configurado a 3 por minuto y por IP; el reloj es de prueba. */
@TestPropertySource(properties = {
        "stockflow.rate-limit.login-per-minute=3",
        "stockflow.rate-limit.global-per-minute=1000"
})
class RateLimitIT extends AbstractPostgresIT {

    private static final String EMAIL = "user@example.test";
    private static final String PASSWORD = "Passw0rd-secret";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                VALUES (?, ?, ?, 'STANDARD', 'ACTIVE', now(), now())
                """, UUID.randomUUID(), EMAIL, new BCryptPasswordEncoder().encode(PASSWORD));
        clock.advance(Duration.ofMinutes(10));
    }

    private MvcResult login(String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    void theLoginLimitRespondsWith429ButDoesNotCountAsALoginAttemptNorLockTheAccount() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(login("Wr0ng-password").getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult limited = login(PASSWORD);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getResponse().getContentAsString()).contains("\"status\":429").doesNotContain("accessToken");
        // Solo los 3 intentos que llegaron al caso de uso cuentan para el bloqueo por cuenta (umbral: 5).
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM users", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class)).isZero();
        // Health queda fuera del límite.
        assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getStatus()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(1));
        assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }
}
