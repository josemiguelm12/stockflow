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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T03-10 de extremo a extremo: forgot 2, reset 2 y change 1 por minuto y por IP; el reloj es de prueba. */
@TestPropertySource(properties = {
        "stockflow.rate-limit.password-forgot-per-minute=2",
        "stockflow.rate-limit.password-reset-per-minute=2",
        "stockflow.rate-limit.password-change-per-minute=1",
        "stockflow.rate-limit.global-per-minute=1000"
})
class PasswordRateLimitIT extends AbstractPostgresIT {

    private static final String EMAIL = "user@example.test";
    private static final String OLD = "Passw0rd-secret";
    private static final String NEW = "N3wPassw0rd-ok";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                VALUES (?, ?, ?, 'STANDARD', 'ACTIVE', now(), now())
                """, UUID.randomUUID(), EMAIL, bcrypt.encode(OLD));
        clock.advance(Duration.ofMinutes(10));
    }

    private MvcResult forgot(String forwardedFor) throws Exception {
        var request = post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult reset() throws Exception {
        return mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"unknown-token\",\"newPassword\":\"" + NEW + "\"}")).andReturn();
    }

    private MvcResult change(String bearer, String current) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/change").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + NEW + "\"}")).andReturn();
    }

    private String loginToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + OLD + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void forgotIsLimitedPerIpAndALimitedRequestDoesNotIssueATokenOrAnEmail() throws Exception {
        assertThat(forgot(null).getResponse().getStatus()).isEqualTo(202);
        assertThat(forgot(null).getResponse().getStatus()).isEqualTo(202);

        MvcResult limited = forgot(null);
        MvcResult evading = forgot("203.0.113.77"); // X-Forwarded-For no evade el límite

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getResponse().getContentAsString()).contains("\"status\":429").doesNotContain(EMAIL);
        assertThat(evading.getResponse().getStatus()).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM one_time_tokens", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbound_emails", Integer.class)).isEqualTo(2);
    }

    @Test
    void resetHasItsOwnLimitIndependentOfForgot() throws Exception {
        forgot(null);
        forgot(null);
        assertThat(forgot(null).getResponse().getStatus()).isEqualTo(429);

        assertThat(reset().getResponse().getStatus()).isEqualTo(400);
        assertThat(reset().getResponse().getStatus()).isEqualTo(400);
        MvcResult limited = reset();

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void changeIsLimitedAndALimitedRequestDoesNotChangeThePassword() throws Exception {
        String bearer = loginToken();
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM users", String.class);

        assertThat(change(bearer, "Wr0ng-password").getResponse().getStatus()).isEqualTo(400);
        MvcResult limited = change(bearer, OLD);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hashBefore);
        assertThat(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + bearer))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void healthIsNeverLimitedAndTheBudgetRenewsInTheNextWindow() throws Exception {
        forgot(null);
        forgot(null);
        assertThat(forgot(null).getResponse().getStatus()).isEqualTo(429);
        for (int i = 0; i < 20; i++) {
            assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        }

        clock.advance(Duration.ofMinutes(1));

        assertThat(forgot(null).getResponse().getStatus()).isEqualTo(202);
    }
}
