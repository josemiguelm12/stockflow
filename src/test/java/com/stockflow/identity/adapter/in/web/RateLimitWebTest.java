package com.stockflow.identity.adapter.in.web;

import com.stockflow.HealthController;
import com.stockflow.identity.application.ActivateAccount;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.Login;
import com.stockflow.identity.application.LoginResult;
import com.stockflow.identity.application.Logout;
import com.stockflow.identity.application.RegisterUser;
import com.stockflow.identity.application.ResendActivation;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T02-08: límites configurables (global 5, login 2 en este test), 429 con Retry-After, sin pausas reales. */
@WebMvcTest(controllers = {AuthController.class, HealthController.class, ActivationLandingController.class})
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=5",
        "stockflow.rate-limit.login-per-minute=2"
})
class RateLimitWebTest {

    private static final String LOGIN_BODY = "{\"email\":\"user@example.test\",\"password\":\"Passw0rd-secret\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    private Login login;
    @MockitoBean
    private RegisterUser registerUser;
    @MockitoBean
    private ActivateAccount activateAccount;
    @MockitoBean
    private ResendActivation resendActivation;
    @MockitoBean
    private Logout logout;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @BeforeEach
    void freshWindow() {
        // El limitador es un singleton del contexto: cada prueba avanza a una ventana nueva y no depende de las demás.
        clock.advance(Duration.ofMinutes(10));
        when(login.login(any(), any())).thenReturn(new LoginResult.InvalidCredentials());
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void login(String ip) throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY));
    }

    @Test
    void loginIsLimitedPerIpAndRespondsWith429AndRetryAfter() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.1")).contentType(MediaType.APPLICATION_JSON)
                            .content(LOGIN_BODY))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.1")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("10.0.0.1"))));

        // La petición rechazada nunca llegó al caso de uso (no cuenta como intento de login).
        verify(login, times(2)).login(any(), any());
    }

    @Test
    void retryAfterShrinksAsTheWindowAdvancesAndTheLimitResetsInTheNextWindow() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("10.0.0.2");
        }
        clock.advance(Duration.ofSeconds(45));
        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.2")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "15"));

        clock.advance(Duration.ofSeconds(15));
        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.2")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void limitsAreCountedPerIpAndForwardedHeadersAreIgnored() throws Exception {
        for (int i = 0; i < 2; i++) {
            login("10.0.0.3");
        }

        // Otra IP no está limitada.
        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.4")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized());
        // Falsificar X-Forwarded-For no evita el límite ni consume el de otra IP.
        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.3")).header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theGlobalLimitAppliesToTheWholeApiAndIsIndependentOfTheLoginLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/auth/me").with(from("10.0.0.5"))).andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/v1/auth/me").with(from("10.0.0.5")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.5")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void healthAndNonApiRoutesAreOutsideTheLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/health").with(from("10.0.0.6"))).andExpect(status().isOk());
            mvc.perform(get("/activate").with(from("10.0.0.6"))).andExpect(status().isOk());
        }
        // Y no consumen el presupuesto de /api/v1/**.
        mvc.perform(get("/api/v1/auth/me").with(from("10.0.0.6"))).andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightIsNotCountedNorLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(options("/api/v1/auth/login").with(from("10.0.0.7"))
                            .header("Origin", "http://localhost:4200")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/auth/login").with(from("10.0.0.7")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized());
    }
}
