package com.stockflow.notification.adapter.in.web;

import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.notification.application.ListNotifications;
import com.stockflow.notification.application.MarkNotificationRead;
import com.stockflow.notification.application.NotificationNotFoundException;
import com.stockflow.notification.application.NotificationPreferences;
import com.stockflow.notification.domain.Notification;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotificationController.class)
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000"
})
class NotificationControllerWebTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NOTIFICATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RESOURCE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant CREATED_AT = Instant.parse("2026-02-01T10:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-02-01T11:00:00Z");
    private static final String BEARER = "Bearer valid-token";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ListNotifications listNotifications;
    @MockitoBean
    private MarkNotificationRead markNotificationRead;
    @MockitoBean
    private NotificationPreferences preferences;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @BeforeEach
    void signedIn() {
        when(authenticateSession.authenticate("valid-token"))
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "user@example.test", "STANDARD", SESSION_ID)));
    }

    private static Notification notification(Instant readAt) {
        return Notification.restore(NOTIFICATION_ID, USER_ID, NotificationType.PERMISSION_REQUEST_REJECTED,
                "Solicitud de permiso rechazada", "Motivo: no procede", NotificationResourceType.PERMISSION_REQUEST,
                RESOURCE_ID, CREATED_AT, readAt);
    }

    // ---- 401 ----

    @Test
    void everyRouteRequiresASession() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/{id}/read", NOTIFICATION_ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"functionalEmailEnabled\":false}")).andExpect(status().isUnauthorized());

        verifyNoInteractions(listNotifications, markNotificationRead, preferences);
    }

    @Test
    void anInvalidTokenIsRejectedWith401() throws Exception {
        when(authenticateSession.authenticate("bad")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized());
    }

    // ---- listado ----

    @Test
    void listReturnsTheCallersPageWithReadFlagAndDates() throws Exception {
        when(listNotifications.list(USER_ID, null, 0, 20)).thenReturn(new ListNotifications.NotificationPage(
                List.of(notification(null), notification(READ_AT)), 0, 20, 2, 1));

        mvc.perform(get("/api/v1/notifications").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.items[0].type").value("PERMISSION_REQUEST_REJECTED"))
                .andExpect(jsonPath("$.items[0].resourceType").value("PERMISSION_REQUEST"))
                .andExpect(jsonPath("$.items[0].resourceId").value(RESOURCE_ID.toString()))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].read").value(true))
                .andExpect(jsonPath("$.items[1].readAt").exists())
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listPassesTheReadFilterAndPagingToTheUseCase() throws Exception {
        when(listNotifications.list(any(), any(), anyInt(), anyInt()))
                .thenReturn(new ListNotifications.NotificationPage(List.of(), 2, 5, 0, 0));

        mvc.perform(get("/api/v1/notifications?read=false&page=2&size=5").header("Authorization", BEARER))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications?read=true").header("Authorization", BEARER))
                .andExpect(status().isOk());

        verify(listNotifications).list(USER_ID, false, 2, 5);
        verify(listNotifications).list(USER_ID, true, 0, 20);
    }

    @Test
    void aClientSuppliedUserIdNeverChangesTheScope() throws Exception {
        when(listNotifications.list(any(), any(), anyInt(), anyInt()))
                .thenReturn(new ListNotifications.NotificationPage(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/notifications?userId=" + OTHER_USER + "&user_id=" + OTHER_USER)
                        .header("Authorization", BEARER).header("X-User-Id", OTHER_USER.toString()))
                .andExpect(status().isOk());

        verify(listNotifications).list(eq(USER_ID), isNull(), eq(0), eq(20));
    }

    @Test
    void invalidFiltersAreControlled400sWithoutEchoingTheValue() throws Exception {
        String body = mvc.perform(get("/api/v1/notifications?read=maybe-secret").header("Authorization", BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("maybe-secret");
        mvc.perform(get("/api/v1/notifications?page=abc").header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications?size=1.5").header("Authorization", BEARER))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(listNotifications);
    }

    @Test
    void outOfRangePagingRejectedByTheUseCaseIsA400WithTheField() throws Exception {
        when(listNotifications.list(any(), any(), anyInt(), anyInt()))
                .thenThrow(new InvalidInputException("size", "must be between 1 and 100"));

        mvc.perform(get("/api/v1/notifications?size=101").header("Authorization", BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    // ---- marcar como leída ----

    @Test
    void markReadReturnsTheNotificationWithItsReadAt() throws Exception {
        when(markNotificationRead.markRead(USER_ID, NOTIFICATION_ID)).thenReturn(notification(READ_AT));

        mvc.perform(post("/api/v1/notifications/{id}/read", NOTIFICATION_ID).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").value("2026-02-01T11:00:00Z"));
    }

    @Test
    void markReadIsIdempotentAndAlwaysUsesTheSessionUser() throws Exception {
        when(markNotificationRead.markRead(USER_ID, NOTIFICATION_ID)).thenReturn(notification(READ_AT));

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/notifications/{id}/read?userId=" + OTHER_USER, NOTIFICATION_ID)
                            .header("Authorization", BEARER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.readAt").value("2026-02-01T11:00:00Z"));
        }

        verify(markNotificationRead, org.mockito.Mockito.times(2)).markRead(USER_ID, NOTIFICATION_ID);
    }

    @Test
    void aForeignOrMissingNotificationIs404WithAGenericBody() throws Exception {
        when(markNotificationRead.markRead(any(), any())).thenThrow(new NotificationNotFoundException());

        String body = mvc.perform(post("/api/v1/notifications/{id}/read", NOTIFICATION_ID)
                        .header("Authorization", BEARER))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andReturn().getResponse().getContentAsString();

        // "instance" solo repite la ruta pedida por el propio cliente; nada del motivo interno ni de la excepción.
        assertThat(body).doesNotContainIgnoringCase("exception").doesNotContain("Notification not found")
                .doesNotContain("userId");
    }

    @Test
    void aMalformedIdIs400() throws Exception {
        mvc.perform(post("/api/v1/notifications/not-a-uuid/read").header("Authorization", BEARER))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(markNotificationRead);
    }

    // ---- preferencias ----

    @Test
    void preferencesDefaultToEnabled() throws Exception {
        when(preferences.isFunctionalEmailEnabled(USER_ID)).thenReturn(true);

        mvc.perform(get("/api/v1/notifications/preferences").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionalEmailEnabled").value(true));
    }

    @Test
    void patchUpdatesOnlyTheSessionUsersPreference() throws Exception {
        when(preferences.updateFunctionalEmail(USER_ID, false)).thenReturn(false);

        mvc.perform(patch("/api/v1/notifications/preferences").header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionalEmailEnabled\":false,\"userId\":\"" + OTHER_USER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionalEmailEnabled").value(false));

        verify(preferences).updateFunctionalEmail(USER_ID, false);
    }

    @Test
    void patchRejectsMissingNullOrNonBooleanValues() throws Exception {
        for (String invalid : List.of("{}", "{\"functionalEmailEnabled\":null}",
                "{\"functionalEmailEnabled\":\"maybe\"}", "{\"functionalEmailEnabled\":[]}", "not-json", "")) {
            mvc.perform(patch("/api/v1/notifications/preferences").header("Authorization", BEARER)
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(preferences);
    }

    @Test
    void routesNotDeclaredStayDeniedByDefault() throws Exception {
        mvc.perform(delete("/api/v1/notifications/{id}", NOTIFICATION_ID).header("Authorization", BEARER))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications/{id}", NOTIFICATION_ID).header("Authorization", BEARER))
                .andExpect(status().isForbidden());
    }
}
