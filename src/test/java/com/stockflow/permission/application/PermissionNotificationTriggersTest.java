package com.stockflow.permission.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.identity.application.UserAdministrationRepository.ManagedUser;
import com.stockflow.permission.domain.InvalidPermissionRequestException;
import com.stockflow.permission.domain.InvalidPermissionRequestTransitionException;
import com.stockflow.permission.domain.PermissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF-NOT-01/02: los avisos se emiten solo tras un evento válido, después de guardarlo, y su fallo lo propaga. */
class PermissionNotificationTriggersTest {

    private static final UUID REQUESTER = UUID.fromString("00000000-0000-0000-0000-00000000f501");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000f502");
    private static final UUID STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000f503");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000f5a1");
    private static final Instant REQUESTED_AT = Instant.parse("2026-04-01T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");

    private final UserAdministrationRepository users = mock(UserAdministrationRepository.class);
    private final PermissionRequestRepository requests = mock(PermissionRequestRepository.class);
    private final UserPermissionRepository grants = mock(UserPermissionRepository.class);
    private final PermissionEventNotifier notifier = mock(PermissionEventNotifier.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final CreatePermissionRequest create = new CreatePermissionRequest(users, requests, clock, notifier);
    private final ApprovePermissionRequest approve = new ApprovePermissionRequest(users, requests, grants, clock,
            notifier);
    private final RejectPermissionRequest reject = new RejectPermissionRequest(users, requests, clock, notifier);

    @BeforeEach
    void actors() {
        when(users.find(any())).thenReturn(Optional.empty());
        user(REQUESTER, "STANDARD", "ACTIVE");
        user(ADMIN, "ADMIN", "ACTIVE");
        user(STANDARD, "STANDARD", "ACTIVE");
    }

    private void user(UUID id, String role, String status) {
        when(users.find(id)).thenReturn(Optional.of(new ManagedUser(id, id + "@example.test", role, status, true)));
    }

    private PermissionRequest pending() {
        return PermissionRequest.create(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUESTED_AT);
    }

    // ---- RF-NOT-01 ----

    @Test
    void creatingARequestNotifiesAfterSavingIt() {
        create.create(REQUESTER, new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE));

        InOrder order = inOrder(requests, notifier);
        order.verify(requests).save(any(PermissionRequest.class));
        order.verify(notifier).requestCreated(any(PermissionRequest.class));
        verify(notifier).requestCreated(org.mockito.ArgumentMatchers.argThat(r -> r.requestedBy().equals(REQUESTER)));
    }

    @Test
    void anInvalidOrUnauthorizedCreationNeverNotifies() {
        user(UNKNOWN(), "STANDARD", "DISABLED");

        assertThat(catchThrowable(() -> create.create(REQUESTER, new CreatePermissionRequest.Command(null))))
                .isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(catchThrowable(() -> create.create(UNKNOWN(),
                new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE))))
                .isInstanceOf(PermissionActorNotAuthorizedException.class);
        assertThat(catchThrowable(() -> create.create(UUID.randomUUID(),
                new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE))))
                .isInstanceOf(PermissionActorNotAuthorizedException.class);

        verifyNoInteractions(notifier);
    }

    private static UUID UNKNOWN() {
        return UUID.fromString("00000000-0000-0000-0000-00000000f5ff");
    }

    @Test
    void aNotifierFailureOnCreationPropagatesSoTheTransactionRollsBack() {
        doThrow(new IllegalStateException("outbox failure (simulated)")).when(notifier).requestCreated(any());

        assertThatThrownBy(() -> create.create(REQUESTER, new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- RF-NOT-02: aprobar ----

    @Test
    void approvingNotifiesTheRequesterAfterSavingTheAppliedRequest() {
        PermissionRequest request = pending();
        when(requests.lockById(REQUEST_ID)).thenReturn(Optional.of(request));

        approve.approve(ADMIN, REQUEST_ID);

        InOrder order = inOrder(requests, notifier);
        order.verify(requests).save(request);
        order.verify(notifier).requestApproved(request);
        verify(notifier, never()).requestRejected(any(), any());
        assertThat(request.status()).isEqualTo(APPLIED);
    }

    @Test
    void anUnauthorizedSelfOrRepeatedApprovalNeverNotifies() {
        PermissionRequest own = PermissionRequest.create(UUID.randomUUID(), ADMIN, PURCHASE_ORDER_CREATE, REQUESTED_AT);
        when(requests.lockById(own.id())).thenReturn(Optional.of(own));
        PermissionRequest applied = PermissionRequest.restore(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE,
                REQUESTED_AT, APPLIED, ADMIN, REQUESTED_AT.plusSeconds(1), null, ADMIN, REQUESTED_AT.plusSeconds(1));
        when(requests.lockById(REQUEST_ID)).thenReturn(Optional.of(applied));

        assertThat(catchThrowable(() -> approve.approve(STANDARD, REQUEST_ID)))
                .isInstanceOf(PermissionActorNotAuthorizedException.class);
        assertThat(catchThrowable(() -> approve.approve(ADMIN, own.id())))
                .isInstanceOf(PermissionSelfResolutionException.class);
        assertThat(catchThrowable(() -> approve.approve(ADMIN, REQUEST_ID)))
                .isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(catchThrowable(() -> approve.approve(ADMIN, UUID.randomUUID())))
                .isInstanceOf(PermissionRequestNotFoundException.class);

        verifyNoInteractions(notifier);
    }

    // ---- RF-NOT-02: rechazar ----

    @Test
    void rejectingNotifiesTheRequesterWithTheLiteralReasonAfterSaving() {
        PermissionRequest request = pending();
        when(requests.lockById(REQUEST_ID)).thenReturn(Optional.of(request));
        String reason = "  No longer needed: role changed  ";

        reject.reject(ADMIN, REQUEST_ID, reason);

        InOrder order = inOrder(requests, notifier);
        order.verify(requests).save(request);
        order.verify(notifier).requestRejected(request, reason);
        verify(notifier, never()).requestApproved(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void aRejectionWithoutAWrittenReasonNeverNotifies(String reason) {
        when(requests.lockById(REQUEST_ID)).thenReturn(Optional.of(pending()));

        assertThat(catchThrowable(() -> reject.reject(ADMIN, REQUEST_ID, reason)))
                .isInstanceOf(InvalidPermissionRequestException.class);

        verifyNoInteractions(notifier);
    }

    @Test
    void anUnauthorizedOrRepeatedRejectionNeverNotifies() {
        PermissionRequest rejected = PermissionRequest.restore(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE,
                REQUESTED_AT, com.stockflow.permission.domain.PermissionRequestStatus.REJECTED, ADMIN,
                REQUESTED_AT.plusSeconds(1), "first reason", null, null);
        when(requests.lockById(REQUEST_ID)).thenReturn(Optional.of(rejected));

        assertThat(catchThrowable(() -> reject.reject(STANDARD, REQUEST_ID, "reason")))
                .isInstanceOf(PermissionActorNotAuthorizedException.class);
        assertThat(catchThrowable(() -> reject.reject(ADMIN, REQUEST_ID, "second reason")))
                .isInstanceOf(InvalidPermissionRequestTransitionException.class);

        verifyNoInteractions(notifier);
    }
}
