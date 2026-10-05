package com.stockflow.identity.adapter.in.web;

import com.stockflow.HealthController;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.ChangePassword;
import com.stockflow.identity.application.InvalidCurrentPasswordException;
import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.identity.application.InvalidPasswordResetTokenException;
import com.stockflow.identity.application.RequestPasswordReset;
import com.stockflow.identity.application.ResetPassword;
import com.stockflow.shared.config.SecurityConfig;
import com.stockflow.support.WebTestClock;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PasswordController.class, PasswordResetLandingController.class, HealthController.class})
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000",
        "stockflow.rate-limit.password-forgot-per-minute=100000",
        "stockflow.rate-limit.password-reset-per-minute=100000",
        "stockflow.rate-limit.password-change-per-minute=100000"
})
class PasswordWebTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String UNIFORM = "If the account is eligible, a password reset email will be sent.";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private RequestPasswordReset requestPasswordReset;
    @MockitoBean
    private ResetPassword resetPassword;
    @MockitoBean
    private ChangePassword changePassword;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    private void signedInAs(String token) {
        when(authenticateSession.authenticate(token))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "user@example.test", "STANDARD", SESSION_ID)));
    }

    // ---- forgot ----

    @Test
    void forgotAlwaysRespondsWithTheSameAcceptedBody() throws Exception {
        String first = mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"exists@example.test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(UNIFORM))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.test\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(first).isEqualTo(second);
        verify(requestPasswordReset).request("exists@example.test");
        verify(requestPasswordReset).request("nobody@example.test");
    }

    @Test
    void forgotWithAMalformedEmailIsAControlled400WithoutEchoingTheValue() throws Exception {
        doThrow(new InvalidInputException("email", "must be a valid email address"))
                .when(requestPasswordReset).request(any());

        String body = mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("not-an-email");
        mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---- reset ----

    @Test
    void resetReturns204WithoutBodyOrToken() throws Exception {
        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"good-token\",\"newPassword\":\"N3wPassw0rd-ok\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(resetPassword).reset("good-token", "N3wPassw0rd-ok");
    }

    @Test
    void resetErrorsAreGenericAndNeverEchoTheTokenOrThePassword() throws Exception {
        doThrow(new InvalidPasswordResetTokenException()).when(resetPassword).reset(any(), any());

        String invalid = mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"secret-token\",\"newPassword\":\"N3wPassw0rd-ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid password reset token"))
                .andReturn().getResponse().getContentAsString();
        assertThat(invalid).doesNotContain("secret-token").doesNotContain("N3wPassw0rd-ok");

        doThrow(new InvalidInputException("newPassword", "must contain at least one digit"))
                .when(resetPassword).reset(any(), any());
        String weak = mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"secret-token\",\"newPassword\":\"weakpassword\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andReturn().getResponse().getContentAsString();
        assertThat(weak).doesNotContain("secret-token").doesNotContain("weakpassword");
    }

    @Test
    void resetRejectsMissingFieldsBeforeReachingTheUseCase() throws Exception {
        for (String body : new String[]{"{}", "{\"token\":\"t\"}", "{\"newPassword\":\"N3wPassw0rd-ok\"}", "oops", ""}) {
            mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(resetPassword);
    }

    // ---- change ----

    @Test
    void changeRequiresABearerToken() throws Exception {
        mvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Passw0rd-secret\",\"newPassword\":\"N3wPassw0rd-ok\"}"))
                .andExpect(status().isUnauthorized());
        when(authenticateSession.authenticate("bad")).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/auth/password/change").header("Authorization", "Bearer bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Passw0rd-secret\",\"newPassword\":\"N3wPassw0rd-ok\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(changePassword);
    }

    @Test
    void changeActsOnTheAuthenticatedUserOnlyAndIgnoresAnyUserIdInTheBody() throws Exception {
        signedInAs("valid-token");

        mvc.perform(post("/api/v1/auth/password/change").header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + OTHER_ID + "\",\"currentPassword\":\"Passw0rd-secret\","
                                + "\"newPassword\":\"N3wPassw0rd-ok\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(changePassword).change(USER_ID, "Passw0rd-secret", "N3wPassw0rd-ok");
    }

    @Test
    void aWrongCurrentPasswordIsAGeneric400() throws Exception {
        signedInAs("valid-token");
        doThrow(new InvalidCurrentPasswordException()).when(changePassword).change(any(), any(), any());

        String body = mvc.perform(post("/api/v1/auth/password/change").header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Wr0ng-password\",\"newPassword\":\"N3wPassw0rd-ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Wr0ng-password").doesNotContain("N3wPassw0rd-ok");
    }

    // ---- landing ----

    @Test
    void theLandingIsServedOnGetWithoutCachingAndNeverCallsAUseCase() throws Exception {
        String csp = mvc.perform(get("/reset-password"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("/api/v1/auth/password/reset")))
                .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).contains("script-src 'sha256-").contains("style-src 'sha256-").contains("connect-src 'self'")
                .contains("frame-ancestors 'none'").contains("form-action 'none'").contains("default-src 'none'");
        verifyNoInteractions(requestPasswordReset, resetPassword, changePassword);
    }

    @Test
    void theLandingTakesTheTokenFromTheFragmentAndRemovesItBeforeAnyRequest() throws Exception {
        String html = mvc.perform(get("/reset-password").param("token", "from-query-must-be-ignored"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("location.hash").contains("history.replaceState");
        assertThat(html.indexOf("history.replaceState")).isLessThan(html.indexOf("fetch("));
        assertThat(html).doesNotContain("from-query-must-be-ignored").doesNotContain("innerHTML")
                .doesNotContain("document.write").doesNotContain("location.search");
        // La página solo envía el token en el cuerpo del POST, al enviar el formulario.
        assertThat(html).contains("addEventListener('submit'").contains("JSON.stringify({ token: token");
    }

    @Test
    void onlyTheDocumentedPasswordRoutesAreOpen() throws Exception {
        mvc.perform(get("/api/v1/auth/password/forgot")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/password/other").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/reset-password")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
