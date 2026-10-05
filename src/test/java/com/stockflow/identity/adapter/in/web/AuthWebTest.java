package com.stockflow.identity.adapter.in.web;

import com.stockflow.HealthController;
import com.stockflow.identity.application.ActivateAccount;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.Login;
import com.stockflow.identity.application.LoginResult;
import com.stockflow.identity.application.Logout;
import com.stockflow.support.WebTestClock;
import com.stockflow.identity.application.EmailAlreadyRegisteredException;
import com.stockflow.identity.application.InvalidActivationTokenException;
import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.identity.application.RegisterUser;
import com.stockflow.identity.application.ResendActivation;
import com.stockflow.shared.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, ActivationLandingController.class, HealthController.class})
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000",
        "stockflow.rate-limit.login-per-minute=100000"
})
class AuthWebTest {

    private static final String CREDENTIALS = "{\"email\":\"user@example.test\",\"password\":\"Passw0rd-secret\"}";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private RegisterUser registerUser;
    @MockitoBean
    private ActivateAccount activateAccount;
    @MockitoBean
    private ResendActivation resendActivation;
    @MockitoBean
    private Login login;
    @MockitoBean
    private Logout logout;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @Test
    void registerReturns201WithoutHashTokenOrPassword() throws Exception {
        UUID id = UUID.randomUUID();
        when(registerUser.register("user@example.test", "Passw0rd-secret"))
                .thenReturn(new RegisterUser.Registered(id, "user@example.test"));

        String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.accountStatus").value("PENDING_ACTIVATION"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Passw0rd").doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("token");
    }

    @Test
    void registerIgnoresClientSuppliedRoleAndStatus() throws Exception {
        when(registerUser.register(any(), any()))
                .thenReturn(new RegisterUser.Registered(UUID.randomUUID(), "user@example.test"));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.test\",\"password\":\"Passw0rd-secret\","
                                + "\"role\":\"ADMIN\",\"accountStatus\":\"ACTIVE\"}"))
                .andExpect(status().isCreated());

        verify(registerUser).register("user@example.test", "Passw0rd-secret");
    }

    @Test
    void invalidBodiesAreRejectedWithAControlledProblem() throws Exception {
        for (String body : new String[]{"{}", "{\"email\":\"\",\"password\":\"x\"}", "not json", ""}) {
            String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("Exception").doesNotContain("at com.stockflow");
        }
        verifyNoInteractions(registerUser);
    }

    @Test
    void domainValidationErrorsAre400WithoutEchoingTheValue() throws Exception {
        doThrow(new InvalidInputException("password", "must contain at least one digit"))
                .when(registerUser).register(any(), any());

        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("Passw0rd-secret");
    }

    @Test
    void duplicateEmailIs409() throws Exception {
        doThrow(new EmailAlreadyRegisteredException()).when(registerUser).register(any(), any());

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unexpectedErrorsAre500WithoutDetails() throws Exception {
        doThrow(new IllegalStateException("select * from users secret-detail")).when(registerUser).register(any(), any());

        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("secret-detail").doesNotContain("select");
    }

    @Test
    void activateReturns204AndInvalidTokenReturns400() throws Exception {
        mvc.perform(post("/api/v1/auth/activate").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"good\"}"))
                .andExpect(status().isNoContent());

        doThrow(new InvalidActivationTokenException()).when(activateAccount).activate("bad");
        mvc.perform(post("/api/v1/auth/activate").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid activation token"));
    }

    @Test
    void resendAlwaysReturnsTheSame202() throws Exception {
        String first = mvc.perform(post("/api/v1/auth/resend-activation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"exists@example.test\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/auth/resend-activation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void onlyTheAuthorizedRoutesAndMethodsAreOpen() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/register")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/auth/register")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(post("/activate")).andExpect(status().isUnauthorized());
    }

    @Test
    void landingIsServedOnGetAndNeverMutatesState() throws Exception {
        String csp = mvc.perform(get("/activate"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("/api/v1/auth/activate")))
                .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).contains("script-src 'sha256-").contains("connect-src 'self'").contains("frame-ancestors 'none'");
        verifyNoInteractions(activateAccount, registerUser, resendActivation);
    }

    // ---- T02: login, /me y logout ----

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void loginReturnsTheTokenEnvelope() throws Exception {
        when(login.login("user@example.test", "Passw0rd-secret"))
                .thenReturn(new LoginResult.Authenticated("jwt-value", 900));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void loginRejectionsAreControlled() throws Exception {
        when(login.login(any(), any())).thenReturn(new LoginResult.InvalidCredentials());
        String first = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"other@example.test\",\"password\":\"Another1-pass\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(first).isEqualTo(second);

        when(login.login(any(), any())).thenReturn(new LoginResult.AccountNotActive());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginWithAMalformedEmailIsA400ProblemWithoutEchoingTheValue() throws Exception {
        when(login.login(any(), any())).thenThrow(new InvalidInputException("email", "must be a valid email address"));

        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"Passw0rd-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("not-an-email").doesNotContain("Passw0rd-secret");
    }

    @Test
    void meReturnsOnlyIdEmailAndRoleForAValidBearer() throws Exception {
        when(authenticateSession.authenticate("valid-token"))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "user@example.test", "STANDARD", SESSION_ID)));

        String body = mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("user@example.test"))
                .andExpect(jsonPath("$.role").value("STANDARD"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SESSION_ID.toString());
    }

    @Test
    void protectedRoutesRejectMissingInvalidAndNonBearerCredentials() throws Exception {
        when(authenticateSession.authenticate("valid-token"))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "user@example.test", "STANDARD", SESSION_ID)));
        when(authenticateSession.authenticate("bad-token")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer bad-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized());
        // Ni query string ni cookie son una fuente válida del token.
        mvc.perform(get("/api/v1/auth/me").param("access_token", "valid-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("access_token", "valid-token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSessionOfTheBearer() throws Exception {
        when(authenticateSession.authenticate("valid-token"))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "user@example.test", "STANDARD", SESSION_ID)));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(logout).logout(SESSION_ID);
    }

    @Test
    void anAuthenticatedUserStillCannotReachUnlistedRoutes() throws Exception {
        when(authenticateSession.authenticate("valid-token"))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "user@example.test", "ADMIN", SESSION_ID)));

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }
}
