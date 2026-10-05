package com.stockflow.identity.adapter.in.web;

import com.stockflow.HealthController;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.ChangePassword;
import com.stockflow.identity.application.RequestPasswordReset;
import com.stockflow.identity.application.ResetPassword;
import com.stockflow.shared.config.SecurityConfig;
import com.stockflow.support.MutableClock;
import com.stockflow.support.WebTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T03-10: límites de forgot (2), reset (3) y change (1) configurables, independientes y sin pausas reales. */
@WebMvcTest(controllers = {PasswordController.class, HealthController.class})
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=6",
        "stockflow.rate-limit.password-forgot-per-minute=2",
        "stockflow.rate-limit.password-reset-per-minute=3",
        "stockflow.rate-limit.password-change-per-minute=1"
})
class PasswordRateLimitWebTest {

    private static final String FORGOT = "/api/v1/auth/password/forgot";
    private static final String RESET = "/api/v1/auth/password/reset";
    private static final String CHANGE = "/api/v1/auth/password/change";
    private static final String FORGOT_BODY = "{\"email\":\"user@example.test\"}";
    private static final String RESET_BODY = "{\"token\":\"t\",\"newPassword\":\"N3wPassw0rd-ok\"}";
    private static final String CHANGE_BODY = "{\"currentPassword\":\"Passw0rd-secret\",\"newPassword\":\"N3wPassw0rd-ok\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    private RequestPasswordReset requestPasswordReset;
    @MockitoBean
    private ResetPassword resetPassword;
    @MockitoBean
    private ChangePassword changePassword;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @BeforeEach
    void freshWindow() {
        clock.advance(Duration.ofMinutes(10));
        when(authenticateSession.authenticate("valid-token")).thenReturn(Optional.of(
                new AuthenticatedUser(UUID.randomUUID(), "user@example.test", "STANDARD", UUID.randomUUID())));
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void forgotIsLimitedPerIpAnd429NeverReachesTheUseCase() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(FORGOT).with(from("10.1.0.1")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post(FORGOT).with(from("10.1.0.1")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("user@example.test"))));

        verify(requestPasswordReset, times(2)).request(any());
    }

    @Test
    void resetAndChangeHaveTheirOwnConfiguredLimits() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(RESET).with(from("10.1.0.2")).contentType(MediaType.APPLICATION_JSON).content(RESET_BODY))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(post(RESET).with(from("10.1.0.2")).contentType(MediaType.APPLICATION_JSON).content(RESET_BODY))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        verify(resetPassword, times(3)).reset(any(), any());

        mvc.perform(post(CHANGE).with(from("10.1.0.3")).header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(CHANGE_BODY))
                .andExpect(status().isNoContent());
        mvc.perform(post(CHANGE).with(from("10.1.0.3")).header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(CHANGE_BODY))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        verify(changePassword, times(1)).change(any(), any(), any());
    }

    @Test
    void theLimitsAreIndependentOfEachOther() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(FORGOT).with(from("10.1.0.4")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post(FORGOT).with(from("10.1.0.4")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                .andExpect(status().isTooManyRequests());

        // forgot agotado no bloquea reset ni change desde la misma IP.
        mvc.perform(post(RESET).with(from("10.1.0.4")).contentType(MediaType.APPLICATION_JSON).content(RESET_BODY))
                .andExpect(status().isNoContent());
        mvc.perform(post(CHANGE).with(from("10.1.0.4")).header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(CHANGE_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void forwardedHeadersDoNotEvadeTheLimitNorSpendAnotherIpsBudget() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(FORGOT).with(from("10.1.0.5")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post(FORGOT).with(from("10.1.0.5")).header("X-Forwarded-For", "203.0.113.50")
                        .header("Forwarded", "for=203.0.113.51").contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post(FORGOT).with(from("10.1.0.6")).header("X-Forwarded-For", "10.1.0.5")
                        .contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    void theLimitResetsInTheNextWindowAndHealthIsNeverLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(FORGOT).with(from("10.1.0.7")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY));
        }
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/health").with(from("10.1.0.7"))).andExpect(status().isOk());
        }

        clock.advance(Duration.ofSeconds(60));

        mvc.perform(post(FORGOT).with(from("10.1.0.7")).contentType(MediaType.APPLICATION_JSON).content(FORGOT_BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    void theGlobalLimitStillAppliesOnTopOfTheRouteLimits() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(RESET).with(from("10.1.0.8")).contentType(MediaType.APPLICATION_JSON).content(RESET_BODY));
        }
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/auth/me").with(from("10.1.0.8")));
        }

        // Séptima petición a /api/v1/** con global = 6: 429 aunque la ruta no tenga límite propio.
        mvc.perform(get("/api/v1/auth/me").with(from("10.1.0.8"))).andExpect(status().isTooManyRequests());
    }
}
