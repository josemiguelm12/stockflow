package com.stockflow.permission.adapter.in.web;

import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.permission.application.ApprovePermissionRequest;
import com.stockflow.permission.application.CreatePermissionRequest;
import com.stockflow.permission.application.ListPendingPermissionRequests;
import com.stockflow.permission.application.PermissionActorNotAuthorizedException;
import com.stockflow.permission.application.PermissionRequestNotFoundException;
import com.stockflow.permission.application.PermissionSelfResolutionException;
import com.stockflow.permission.application.RejectPermissionRequest;
import com.stockflow.permission.domain.InvalidPermissionRequestException;
import com.stockflow.permission.domain.InvalidPermissionRequestTransitionException;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A2-T04: frontera HTTP de Gestión de Permisos. Los casos de uso son dobles; el actor sale siempre del Bearer. */
@WebMvcTest(controllers = PermissionRequestController.class)
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000"
})
class PermissionRequestWebTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    private static final UUID STANDARD_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b2");
    private static final UUID REQUEST_ID = UUID.fromString("cccccccc-0000-0000-0000-0000000000c3");
    private static final String BASE = "/api/v1/permission-requests";
    private static final String PENDING_PATH = BASE + "/pending";
    private static final String APPROVE = BASE + "/" + REQUEST_ID + "/approve";
    private static final String REJECT = BASE + "/" + REQUEST_ID + "/reject";
    private static final String CREATE_BODY = "{\"permissionCode\":\"PURCHASE_ORDER_CREATE\"}";
    private static final String REASON = "  Not needed for the current role  ";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private CreatePermissionRequest createPermissionRequest;
    @MockitoBean
    private ListPendingPermissionRequests listPendingPermissionRequests;
    @MockitoBean
    private ApprovePermissionRequest approvePermissionRequest;
    @MockitoBean
    private RejectPermissionRequest rejectPermissionRequest;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @BeforeEach
    void sessions() {
        when(authenticateSession.authenticate("admin-token")).thenReturn(Optional.of(
                new AuthenticatedUser(ADMIN_ID, "admin@example.test", "ADMIN", UUID.randomUUID())));
        when(authenticateSession.authenticate("standard-token")).thenReturn(Optional.of(
                new AuthenticatedUser(STANDARD_ID, "user@example.test", "STANDARD", UUID.randomUUID())));
        when(authenticateSession.authenticate("bad")).thenReturn(Optional.empty());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static List<MockHttpServletRequestBuilder> allEndpoints(String token) {
        return List.of(
                as(json(post(BASE), CREATE_BODY), token),
                as(get(PENDING_PATH), token),
                as(post(APPROVE), token),
                as(json(post(REJECT), "{\"reason\":\"" + REASON + "\"}"), token));
    }

    private void verifyNoUseCaseCalls() {
        verifyNoInteractions(createPermissionRequest, listPendingPermissionRequests, approvePermissionRequest,
                rejectPermissionRequest);
    }

    private static void expectProblem(ResultActions result, int status, String title) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").value(title));
    }

    // ---- 1: sin Bearer válido ----

    @Test
    void everyPermissionEndpointIs401ProblemWithoutAValidBearer() throws Exception {
        for (String token : new String[]{null, "bad"}) {
            for (var request : allEndpoints(token)) {
                expectProblem(mvc.perform(request), 401, "Unauthorized");
            }
        }
        verifyNoUseCaseCalls();
    }

    // ---- 2: crear ----

    @Test
    void aStandardCreatesAPendingRequestAsTheBearerPrincipal() throws Exception {
        UUID created = UUID.fromString("dddddddd-0000-0000-0000-0000000000d4");
        when(createPermissionRequest.create(any(), any()))
                .thenReturn(new CreatePermissionRequest.Result(created, PENDING));

        mvc.perform(as(json(post(BASE), CREATE_BODY), "standard-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(createPermissionRequest).create(STANDARD_ID, new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE));
    }

    @Test
    void forgedIdentityFieldsInTheBodyNeverChangeTheActor() throws Exception {
        when(createPermissionRequest.create(any(), any()))
                .thenReturn(new CreatePermissionRequest.Result(REQUEST_ID, PENDING));
        String forged = "{\"permissionCode\":\"PURCHASE_ORDER_CREATE\",\"userId\":\"" + ADMIN_ID + "\","
                + "\"requestedBy\":\"" + ADMIN_ID + "\",\"role\":\"ADMIN\",\"status\":\"APPLIED\","
                + "\"requestedAt\":\"2020-01-01T00:00:00Z\"}";

        mvc.perform(as(json(post(BASE), forged), "standard-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(createPermissionRequest).create(STANDARD_ID, new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE));
    }

    @Test
    void anInvalidPermissionCodeOrBodyIs400BeforeTheUseCase() throws Exception {
        for (String body : new String[]{"{}", "{\"permissionCode\":null}", "{\"permissionCode\":\"ADMIN\"}",
                "{\"permissionCode\":\"purchase_order_create\"}", "{\"permissionCode\":1}", "oops", "", "null"}) {
            String response = mvc.perform(as(json(post(BASE), body), "standard-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).as(body).doesNotContain("purchase_order_create").doesNotContain("oops")
                    .doesNotContain("Exception").doesNotContain("ADMIN");
        }
        verifyNoUseCaseCalls();
    }

    // ---- 3: listar ----

    @Test
    void anAdminListsPendingRequestsWithOnlyTheAllowedFields() throws Exception {
        Instant requestedAt = Instant.parse("2026-04-01T09:00:00Z");
        when(listPendingPermissionRequests.list(ADMIN_ID)).thenReturn(List.of(
                new ListPendingPermissionRequests.Item(REQUEST_ID, STANDARD_ID, PURCHASE_ORDER_CREATE, requestedAt)));

        String body = mvc.perform(as(get(PENDING_PATH), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$[0].requestedBy").value(STANDARD_ID.toString()))
                .andExpect(jsonPath("$[0].permissionCode").value("PURCHASE_ORDER_CREATE"))
                .andExpect(jsonPath("$[0].requestedAt").value("2026-04-01T09:00:00Z"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("reason").doesNotContain("decided").doesNotContain("applied")
                .doesNotContain("status");
    }

    @Test
    void aStandardReachesTheSameRouteAndOnlyItsPrincipalIsPassed() throws Exception {
        when(listPendingPermissionRequests.list(STANDARD_ID)).thenReturn(List.of());

        mvc.perform(as(get(PENDING_PATH).param("userId", ADMIN_ID.toString()).param("requestedBy",
                        ADMIN_ID.toString()).param("role", "ADMIN"), "standard-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(listPendingPermissionRequests).list(STANDARD_ID);
    }

    // ---- 4 y 5: resolver ----

    @Test
    void aStandardGets403OnApproveAndRejectWithoutReachingTheUseCases() throws Exception {
        expectProblem(mvc.perform(as(post(APPROVE), "standard-token")), 403, "Forbidden");
        expectProblem(mvc.perform(as(json(post(REJECT), "{\"reason\":\"x\"}"), "standard-token")), 403, "Forbidden");

        verifyNoUseCaseCalls();
    }

    @Test
    void anAdminApprovesAndRejectsAsTheBearerPrincipalWithTheLiteralReason() throws Exception {
        mvc.perform(as(post(APPROVE), "admin-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(as(json(post(REJECT), "{\"reason\":\"" + REASON + "\",\"decidedBy\":\"" + STANDARD_ID + "\"}"),
                        "admin-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(approvePermissionRequest).approve(ADMIN_ID, REQUEST_ID);
        verify(rejectPermissionRequest).reject(ADMIN_ID, REQUEST_ID, REASON);
    }

    // ---- 6: motivo ----

    @Test
    void aMissingOrBlankReasonIs400AndRejectIsNeverInvoked() throws Exception {
        for (String body : new String[]{"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}",
                "{\"reason\":\"\\t\\n\"}", "null", "", "oops"}) {
            expectProblem(mvc.perform(as(json(post(REJECT), body), "admin-token")), 400, "Invalid request");
        }
        expectProblem(mvc.perform(as(post(REJECT), "admin-token")), 400, "Invalid request");

        verifyNoInteractions(rejectPermissionRequest);
    }

    // ---- 7: errores de los casos de uso ----

    @Test
    void useCaseErrorsMapToGenericProblemsWithoutInternalDetails() throws Exception {
        doThrow(new PermissionRequestNotFoundException()).when(approvePermissionRequest).approve(any(), any());
        assertGeneric(mvc.perform(as(post(APPROVE), "admin-token")), 404, "Not found");

        doThrow(new InvalidPermissionRequestTransitionException(APPLIED, APPROVED))
                .when(approvePermissionRequest).approve(any(), any());
        assertGeneric(mvc.perform(as(post(APPROVE), "admin-token")), 409, "Conflict");

        doThrow(new PermissionSelfResolutionException()).when(rejectPermissionRequest).reject(any(), any(), any());
        assertGeneric(mvc.perform(as(json(post(REJECT), "{\"reason\":\"" + REASON + "\"}"), "admin-token")),
                409, "Conflict");

        doThrow(new PermissionActorNotAuthorizedException()).when(listPendingPermissionRequests).list(any());
        assertGeneric(mvc.perform(as(get(PENDING_PATH), "admin-token")), 403, "Forbidden");

        doThrow(new InvalidPermissionRequestException("permissionCode is required"))
                .when(createPermissionRequest).create(any(), any());
        assertGeneric(mvc.perform(as(json(post(BASE), CREATE_BODY), "standard-token")), 400, "Invalid request");

        doThrow(new IllegalStateException("boom select * from users")).when(approvePermissionRequest)
                .approve(any(), any());
        assertGeneric(mvc.perform(as(post(APPROVE), "admin-token")), 500, "Internal server error");
    }

    private static void assertGeneric(ResultActions result, int status, String title) throws Exception {
        expectProblem(result, status, title);
        String body = withoutInstance(result.andReturn().getResponse().getContentAsString());
        assertThat(body).doesNotContain(REQUEST_ID.toString()).doesNotContain(ADMIN_ID.toString())
                .doesNotContain(STANDARD_ID.toString()).doesNotContain("Exception").doesNotContain("APPLIED")
                .doesNotContain("APPROVED").doesNotContain("permissionCode").doesNotContain("select")
                .doesNotContain("boom").doesNotContain(REASON.trim());
    }

    /**
     * Spring rellena {@code instance} con la ruta que pidió el propio cliente (comportamiento común a toda la API);
     * el resto del cuerpo no debe repetir ningún valor.
     */
    private static String withoutInstance(String body) {
        return body.replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    @Test
    void aMalformedRequestIdIs400WithoutEchoingItAndWithoutCallingTheUseCases() throws Exception {
        for (String bad : new String[]{"not-a-uuid", "123", "zzzzzzzz-0000-0000-0000-000000000003"}) {
            String approve = mvc.perform(as(post(BASE + "/" + bad + "/approve"), "admin-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andReturn().getResponse().getContentAsString();
            String reject = mvc.perform(as(json(post(BASE + "/" + bad + "/reject"), "{\"reason\":\"x\"}"),
                            "admin-token"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(withoutInstance(approve)).doesNotContain(bad);
            assertThat(withoutInstance(reject)).doesNotContain(bad);
        }
        verifyNoUseCaseCalls();
    }

    // ---- 8: rutas no declaradas ----

    @Test
    void undeclaredRoutesAndMethodsStayClosed() throws Exception {
        List<MockHttpServletRequestBuilder> undeclared = List.of(
                get(BASE), get(BASE + "/" + REQUEST_ID), delete(BASE + "/" + REQUEST_ID),
                put(BASE + "/" + REQUEST_ID), patch(BASE + "/" + REQUEST_ID), post(PENDING_PATH),
                get(APPROVE), get(REJECT), post(BASE + "/" + REQUEST_ID + "/apply"),
                get(PENDING_PATH + "/all"), get(BASE + "/admin/pending"), post(BASE + "/" + REQUEST_ID));
        for (var request : undeclared) {
            mvc.perform(as(request, "admin-token")).andExpect(status().isForbidden());
        }
        for (var request : List.of(get(BASE), post(BASE + "/" + REQUEST_ID + "/apply"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoUseCaseCalls();
    }
}
