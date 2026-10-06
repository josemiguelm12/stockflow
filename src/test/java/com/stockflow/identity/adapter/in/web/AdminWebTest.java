package com.stockflow.identity.adapter.in.web;

import com.stockflow.HealthController;
import com.stockflow.identity.application.AdminActorNotAuthorizedException;
import com.stockflow.identity.application.AdminConflictException;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.ChangeUserRole;
import com.stockflow.identity.application.ChangeUserStatus;
import com.stockflow.identity.application.ForcePasswordReset;
import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.identity.application.ListUsers;
import com.stockflow.identity.application.UserAdministrationRepository.UserSummary;
import com.stockflow.identity.application.UserNotFoundException;
import com.stockflow.shared.config.SecurityConfig;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AdminController.class, HealthController.class})
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000"
})
class AdminWebTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID STANDARD_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID TARGET = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final String USERS = "/api/v1/admin/users";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ListUsers listUsers;
    @MockitoBean
    private ChangeUserRole changeUserRole;
    @MockitoBean
    private ChangeUserStatus changeUserStatus;
    @MockitoBean
    private ForcePasswordReset forcePasswordReset;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @BeforeEach
    void sessions() {
        when(authenticateSession.authenticate("admin-token")).thenReturn(Optional.of(
                new AuthenticatedUser(ADMIN_ID, "admin@example.test", "ADMIN", UUID.randomUUID())));
        when(authenticateSession.authenticate("standard-token")).thenReturn(Optional.of(
                new AuthenticatedUser(STANDARD_ID, "user@example.test", "STANDARD", UUID.randomUUID())));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private List<MockHttpServletRequestBuilder> allEndpoints(String token) {
        return List.of(
                as(get(USERS), token),
                as(patch(USERS + "/" + TARGET + "/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"), token),
                as(patch(USERS + "/" + TARGET + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountStatus\":\"DISABLED\"}"), token),
                as(post(USERS + "/" + TARGET + "/force-password-reset"), token));
    }

    // ---- T04-01 ----

    @Test
    void everyAdminEndpointIs401WithoutAValidBearer() throws Exception {
        when(authenticateSession.authenticate("bad")).thenReturn(Optional.empty());
        for (var request : allEndpoints(null)) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (var request : allEndpoints("bad")) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(listUsers, changeUserRole, changeUserStatus, forcePasswordReset);
    }

    @Test
    void everyAdminEndpointIs403ForAStandardUserAndNeverReachesTheUseCases() throws Exception {
        for (var request : allEndpoints("standard-token")) {
            mvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403));
        }
        verifyNoInteractions(listUsers, changeUserRole, changeUserStatus, forcePasswordReset);
    }

    // ---- T04-02 ----

    @Test
    void theListHasExactlyTheAllowedFields() throws Exception {
        Instant created = Instant.parse("2026-10-05T12:00:00Z");
        when(listUsers.list(0, 20)).thenReturn(new ListUsers.UserPage(List.of(new UserSummary(TARGET,
                "normalized@example.com", "ADMIN", "ACTIVE", false, created, created)), 0, 20, 1, 1));

        String body = mvc.perform(as(get(USERS), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(TARGET.toString()))
                .andExpect(jsonPath("$.items[0].email").value("normalized@example.com"))
                .andExpect(jsonPath("$.items[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.items[0].accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].passwordResetRequired").value(false))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].updatedAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("failed")
                .doesNotContainIgnoringCase("locked").doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("session");
    }

    @Test
    void invalidPagingIsAControlled400() throws Exception {
        doThrow(new InvalidInputException("size", "must be between 1 and 100")).when(listUsers).list(0, 101);

        mvc.perform(as(get(USERS).param("size", "101"), "admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        for (String bad : new String[]{"abc", "1.5", ""}) {
            mvc.perform(as(get(USERS).param("page", bad.isEmpty() ? "--" : bad), "admin-token"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- T04-03 / cuerpos cerrados ----

    @Test
    void roleChangesUseTheActorFromTheBearer() throws Exception {
        mvc.perform(as(patch(USERS + "/" + TARGET + "/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"), "admin-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(changeUserRole).change(ADMIN_ID, TARGET, "ADMIN");
    }

    @Test
    void requestBodiesAreClosedAndUnknownPropertiesAreRejected() throws Exception {
        for (String body : new String[]{
                "{\"role\":\"ADMIN\",\"userId\":\"" + ADMIN_ID + "\"}",
                "{\"role\":\"ADMIN\",\"email\":\"x@example.test\"}",
                "{\"role\":\"ADMIN\",\"passwordHash\":\"x\"}",
                "{}", "{\"Role\":\"ADMIN\"}", "{\"role\":1}", "{\"role\":null}", "[\"ADMIN\"]", "oops", ""}) {
            mvc.perform(as(patch(USERS + "/" + TARGET + "/role").contentType(MediaType.APPLICATION_JSON).content(body),
                            "admin-token"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(patch(USERS + "/" + TARGET + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountStatus\":\"DISABLED\",\"role\":\"ADMIN\"}"), "admin-token"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(changeUserRole, changeUserStatus);
    }

    @Test
    void aMalformedUuidIs400AndAnUnknownUserIs404WithoutDetails() throws Exception {
        for (String bad : new String[]{"not-a-uuid", "123", "zzzzzzzz-0000-0000-0000-000000000003"}) {
            mvc.perform(as(post(USERS + "/" + bad + "/force-password-reset"), "admin-token"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(forcePasswordReset);

        doThrow(new UserNotFoundException()).when(forcePasswordReset).force(any(), any());
        String body = mvc.perform(as(post(USERS + "/" + TARGET + "/force-password-reset"), "admin-token"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception").doesNotContain("select").doesNotContain(TARGET.toString() + "\"x");
    }

    @Test
    void conflictsAreAGeneric409AndARevokedActorIs403() throws Exception {
        doThrow(new AdminConflictException("last_active_admin")).when(changeUserStatus).change(any(), any(), any());
        String body = mvc.perform(as(patch(USERS + "/" + TARGET + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountStatus\":\"DISABLED\"}"), "admin-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("last_active_admin").doesNotContain("last").doesNotContain("self");

        doThrow(new AdminActorNotAuthorizedException()).when(changeUserRole).change(any(), any(), any());
        mvc.perform(as(patch(USERS + "/" + TARGET + "/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STANDARD\"}"), "admin-token"))
                .andExpect(status().isForbidden());
    }

    // ---- T04-07 ----

    @Test
    void forcePasswordResetIs202WithoutTokenOrLinkAndAcceptsNoBody() throws Exception {
        String body = mvc.perform(as(post(USERS + "/" + TARGET + "/force-password-reset"), "admin-token"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEmpty();
        verify(forcePasswordReset).force(ADMIN_ID, TARGET);
    }

    /** T04-H01: el endpoint no acepta cuerpo; cualquier contenido es 400 y el caso de uso no se ejecuta. */
    @Test
    void forcePasswordResetRejectsAnyBodyBeforeReachingTheUseCase() throws Exception {
        for (String body : new String[]{"{\"userId\":\"" + ADMIN_ID + "\"}", "{\"email\":\"x@example.test\"}", "{}", "null", " "}) {
            String response = mvc.perform(as(post(USERS + "/" + TARGET + "/force-password-reset")
                            .contentType(MediaType.APPLICATION_JSON).content(body), "admin-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("x@example.test");
        }
        mvc.perform(as(post(USERS + "/" + TARGET + "/force-password-reset")
                        .contentType(MediaType.TEXT_PLAIN).content("anything"), "admin-token"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(forcePasswordReset);

        mvc.perform(as(post(USERS + "/" + TARGET + "/force-password-reset")
                        .contentType(MediaType.APPLICATION_JSON), "admin-token"))
                .andExpect(status().isAccepted());
        verify(forcePasswordReset).force(ADMIN_ID, TARGET);
    }

    @Test
    void otherAdminRoutesAndMethodsStayClosed() throws Exception {
        mvc.perform(as(post(USERS), "admin-token")).andExpect(status().isForbidden());
        mvc.perform(as(get(USERS + "/" + TARGET), "admin-token")).andExpect(status().isForbidden());
        mvc.perform(as(patch(USERS + "/" + TARGET + "/email").contentType(MediaType.APPLICATION_JSON).content("{}"),
                "admin-token")).andExpect(status().isForbidden());
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(USERS + "/" + TARGET),
                "admin-token")).andExpect(status().isForbidden());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
