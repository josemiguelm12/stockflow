package com.stockflow.permission.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.permission.domain.InvalidPermissionRequestException;
import com.stockflow.permission.domain.InvalidPermissionRequestTransitionException;
import com.stockflow.permission.domain.PermissionCode;
import com.stockflow.permission.domain.PermissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static com.stockflow.permission.domain.PermissionRequestStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Casos de uso de Gestión de Permisos con fakes en memoria y reloj fijo. Los fakes guardan copias: una mutación del
 * agregado que no llegue a {@code save} no altera lo almacenado. La atomicidad real se prueba en las IT.
 */
class PermissionApplicationTest {

    private static final UUID STANDARD_USER = UUID.fromString("00000000-0000-0000-0000-00000000a301");
    private static final UUID OTHER_STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000a302");
    private static final UUID ADMIN_USER = UUID.fromString("00000000-0000-0000-0000-00000000a303");
    private static final UUID OTHER_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a304");
    private static final UUID UNKNOWN_USER = UUID.fromString("00000000-0000-0000-0000-00000000a3ff");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b301");
    private static final UUID SECOND_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b302");
    private static final UUID THIRD_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b303");
    private static final UUID UNKNOWN_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b3ff");
    private static final Instant REQUESTED_AT = Instant.parse("2026-04-01T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
    // Espacios en los extremos: el motivo se guarda literal.
    private static final String REASON = "  Not needed for the current role  ";

    private final FakeUsers users = new FakeUsers();
    private final FakeRequests requests = new FakeRequests();
    private final FakeGrants grants = new FakeGrants();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final CreatePermissionRequest create = new CreatePermissionRequest(users, requests, clock);
    private final ListPendingPermissionRequests list = new ListPendingPermissionRequests(users, requests);
    private final ApprovePermissionRequest approve = new ApprovePermissionRequest(users, requests, grants, clock);
    private final RejectPermissionRequest reject = new RejectPermissionRequest(users, requests, clock);
    private final PermissionChecker checker = new PermissionChecker(grants);

    {
        users.put(STANDARD_USER, "STANDARD", "ACTIVE");
        users.put(OTHER_STANDARD, "STANDARD", "ACTIVE");
        users.put(ADMIN_USER, "ADMIN", "ACTIVE");
        users.put(OTHER_ADMIN, "ADMIN", "ACTIVE");
    }

    // ---- RF-GP-01: crear ----

    @Test
    void anActiveStandardCreatesAPendingRequestWhoseDataComesFromTheUseCase() {
        // Arrange
        CreatePermissionRequest.Command command = new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE);

        // Act
        CreatePermissionRequest.Result result = create.create(STANDARD_USER, command);

        // Assert
        assertThat(result.status()).isEqualTo(PENDING);
        assertThat(requests.saves).isEqualTo(1);
        PermissionRequest stored = requests.stored(result.id());
        assertThat(stored.id()).isEqualTo(result.id());
        assertThat(stored.requestedBy()).isEqualTo(STANDARD_USER);
        assertThat(stored.permissionCode()).isEqualTo(PURCHASE_ORDER_CREATE);
        assertThat(stored.requestedAt()).isEqualTo(NOW);
        assertThat(stored.status()).isEqualTo(PENDING);
        assertThat(stored.decidedBy()).isEmpty();
        assertThat(stored.appliedBy()).isEmpty();
    }

    @Test
    void eachCreationGetsItsOwnRandomId() {
        // Arrange
        CreatePermissionRequest.Command command = new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE);

        // Act
        UUID first = create.create(STANDARD_USER, command).id();
        UUID second = create.create(STANDARD_USER, command).id();

        // Assert
        assertThat(first).isNotEqualTo(second);
        assertThat(requests.all()).hasSize(2);
    }

    @Test
    void anActiveAdminMayAlsoRequestAPermission() {
        // Arrange
        CreatePermissionRequest.Command command = new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE);

        // Act
        CreatePermissionRequest.Result result = create.create(ADMIN_USER, command);

        // Assert
        assertThat(requests.stored(result.id()).requestedBy()).isEqualTo(ADMIN_USER);
        assertThat(result.status()).isEqualTo(PENDING);
    }

    @Test
    void aMissingPermissionCodeIsRejectedBeforeAnyLookupOrSave() {
        // Arrange
        CreatePermissionRequest.Command noCode = new CreatePermissionRequest.Command(null);

        // Act
        Throwable withoutCode = catchThrowable(() -> create.create(STANDARD_USER, noCode));
        Throwable withoutCommand = catchThrowable(() -> create.create(STANDARD_USER, null));

        // Assert
        assertThat(withoutCode).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutCommand).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(users.lookups).isZero();
        assertThat(requests.saves).isZero();
    }

    @Test
    void disabledPendingOrUnknownActorsCannotCreate() {
        // Arrange
        users.put(STANDARD_USER, "STANDARD", "DISABLED");
        users.put(OTHER_STANDARD, "STANDARD", "PENDING_ACTIVATION");
        CreatePermissionRequest.Command command = new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE);

        // Act
        List<Throwable> failures = List.of(
                catchThrowable(() -> create.create(STANDARD_USER, command)),
                catchThrowable(() -> create.create(OTHER_STANDARD, command)),
                catchThrowable(() -> create.create(UNKNOWN_USER, command)));

        // Assert
        assertThat(failures).allSatisfy(f -> assertThat(f).isInstanceOf(PermissionActorNotAuthorizedException.class));
        assertThat(requests.saves).isZero();
    }

    // ---- RF-GP-02: listar ----

    @Test
    void anActiveAdminListsEveryPendingRequestAndNothingElse() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        requests.seed(pending(SECOND_REQUEST_ID, OTHER_STANDARD));
        PermissionRequest resolved = pending(THIRD_REQUEST_ID, OTHER_STANDARD);
        resolved.reject(ADMIN_USER, NOW, REASON);
        requests.seed(resolved);

        // Act
        List<ListPendingPermissionRequests.Item> items = list.list(ADMIN_USER);

        // Assert
        assertThat(items).containsExactly(
                new ListPendingPermissionRequests.Item(REQUEST_ID, STANDARD_USER, PURCHASE_ORDER_CREATE, REQUESTED_AT),
                new ListPendingPermissionRequests.Item(SECOND_REQUEST_ID, OTHER_STANDARD, PURCHASE_ORDER_CREATE,
                        REQUESTED_AT));
    }

    @Test
    void anActiveStandardOnlySeesItsOwnPendingRequests() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        requests.seed(pending(SECOND_REQUEST_ID, OTHER_STANDARD));
        requests.seed(pending(THIRD_REQUEST_ID, ADMIN_USER));

        // Act
        List<ListPendingPermissionRequests.Item> items = list.list(STANDARD_USER);

        // Assert
        assertThat(items).extracting(ListPendingPermissionRequests.Item::id).containsExactly(REQUEST_ID);
        assertThat(items).extracting(ListPendingPermissionRequests.Item::requestedBy).containsOnly(STANDARD_USER);
        assertThat(requests.globalListings).isZero();
    }

    @Test
    void theListingScopeFollowsThePersistedRoleAtCallTime() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        requests.seed(pending(SECOND_REQUEST_ID, OTHER_STANDARD));
        users.put(ADMIN_USER, "STANDARD", "ACTIVE");

        // Act
        List<ListPendingPermissionRequests.Item> demoted = list.list(ADMIN_USER);
        users.put(STANDARD_USER, "ADMIN", "ACTIVE");
        List<ListPendingPermissionRequests.Item> promoted = list.list(STANDARD_USER);

        // Assert
        assertThat(demoted).isEmpty();
        assertThat(promoted).hasSize(2);
    }

    @Test
    void inactiveOrUnknownActorsCannotList() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        users.put(ADMIN_USER, "ADMIN", "DISABLED");
        users.put(STANDARD_USER, "STANDARD", "DISABLED");

        // Act
        List<Throwable> failures = List.of(
                catchThrowable(() -> list.list(ADMIN_USER)),
                catchThrowable(() -> list.list(STANDARD_USER)),
                catchThrowable(() -> list.list(UNKNOWN_USER)));

        // Assert
        assertThat(failures).allSatisfy(f -> assertThat(f).isInstanceOf(PermissionActorNotAuthorizedException.class));
        assertThat(requests.globalListings).isZero();
    }

    // ---- RF-GP-03 / RF-GP-05: aprobar y aplicar ----

    @Test
    void anAdminApprovalAppliesThePermissionAndRecordsActorAndInstant() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));

        // Act
        approve.approve(ADMIN_USER, REQUEST_ID);

        // Assert
        PermissionRequest stored = requests.stored(REQUEST_ID);
        assertThat(stored.status()).isEqualTo(APPLIED);
        assertThat(stored.decidedBy()).contains(ADMIN_USER);
        assertThat(stored.decidedAt()).contains(NOW);
        assertThat(stored.appliedBy()).contains(ADMIN_USER);
        assertThat(stored.appliedAt()).contains(NOW);
        assertThat(stored.rejectionReason()).isEmpty();
        assertThat(grants.all()).singleElement().satisfies(grant -> {
            assertThat(grant.userId()).isEqualTo(STANDARD_USER);
            assertThat(grant.permissionCode()).isEqualTo(PURCHASE_ORDER_CREATE);
            assertThat(grant.sourceRequestId()).isEqualTo(REQUEST_ID);
            assertThat(grant.grantedAt()).isEqualTo(NOW);
        });
        assertThat(checker.hasPermission(STANDARD_USER, PURCHASE_ORDER_CREATE)).isTrue();
    }

    @Test
    void aSecondApprovalFailsOnTheTransitionAndChangesNothing() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        approve.approve(ADMIN_USER, REQUEST_ID);
        PermissionRequest before = requests.stored(REQUEST_ID);

        // Act
        Throwable thrown = catchThrowable(() -> approve.approve(OTHER_ADMIN, REQUEST_ID));

        // Assert
        assertThat(thrown).isInstanceOfSatisfying(InvalidPermissionRequestTransitionException.class, e -> {
            assertThat(e.from()).isEqualTo(APPLIED);
            assertThat(e.to()).isEqualTo(APPROVED);
        });
        assertSameRequest(requests.stored(REQUEST_ID), before);
        assertThat(grants.all()).hasSize(1);
    }

    @Test
    void aRejectedRequestCanNeverBeApprovedOrApplied() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        reject.reject(ADMIN_USER, REQUEST_ID, REASON);
        PermissionRequest before = requests.stored(REQUEST_ID);

        // Act
        Throwable thrown = catchThrowable(() -> approve.approve(OTHER_ADMIN, REQUEST_ID));

        // Assert
        assertThat(thrown).isInstanceOfSatisfying(InvalidPermissionRequestTransitionException.class, e -> {
            assertThat(e.from()).isEqualTo(REJECTED);
            assertThat(e.to()).isEqualTo(APPROVED);
        });
        assertSameRequest(requests.stored(REQUEST_ID), before);
        assertThat(requests.stored(REQUEST_ID).appliedBy()).isEmpty();
        assertThat(grants.all()).isEmpty();
    }

    @Test
    void approvingAnUnknownRequestFailsWithoutEffects() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));

        // Act
        Throwable thrown = catchThrowable(() -> approve.approve(ADMIN_USER, UNKNOWN_REQUEST_ID));

        // Assert
        assertThat(thrown).isInstanceOf(PermissionRequestNotFoundException.class);
        assertThat(grants.all()).isEmpty();
        assertThat(requests.saves).isZero();
    }

    // ---- RF-GP-04: rechazar ----

    @Test
    void anAdminRejectionKeepsTheLiteralReason() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));

        // Act
        reject.reject(ADMIN_USER, REQUEST_ID, REASON);

        // Assert
        PermissionRequest stored = requests.stored(REQUEST_ID);
        assertThat(stored.status()).isEqualTo(REJECTED);
        assertThat(stored.rejectionReason()).contains(REASON);
        assertThat(stored.decidedBy()).contains(ADMIN_USER);
        assertThat(stored.decidedAt()).contains(NOW);
        assertThat(stored.appliedBy()).isEmpty();
        assertThat(grants.all()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n "})
    void aRejectionWithoutAWrittenReasonChangesNothing(String reason) {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        PermissionRequest before = requests.stored(REQUEST_ID);

        // Act
        Throwable thrown = catchThrowable(() -> reject.reject(ADMIN_USER, REQUEST_ID, reason));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class);
        assertSameRequest(requests.stored(REQUEST_ID), before);
        assertThat(requests.saves).isZero();
    }

    @Test
    void anAppliedRequestCannotBeRejectedAfterwards() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        approve.approve(ADMIN_USER, REQUEST_ID);
        PermissionRequest before = requests.stored(REQUEST_ID);

        // Act
        Throwable thrown = catchThrowable(() -> reject.reject(OTHER_ADMIN, REQUEST_ID, REASON));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertSameRequest(requests.stored(REQUEST_ID), before);
        assertThat(grants.all()).hasSize(1);
    }

    // ---- RF-GP-07 / RD-06: auto-resolución y actor ----

    @Test
    void anAdminCannotApproveOrRejectItsOwnRequest() {
        // Arrange
        requests.seed(pending(REQUEST_ID, ADMIN_USER));
        PermissionRequest before = requests.stored(REQUEST_ID);

        // Act
        Throwable approval = catchThrowable(() -> approve.approve(ADMIN_USER, REQUEST_ID));
        Throwable rejection = catchThrowable(() -> reject.reject(ADMIN_USER, REQUEST_ID, REASON));

        // Assert
        assertThat(approval).isInstanceOf(PermissionSelfResolutionException.class);
        assertThat(rejection).isInstanceOf(PermissionSelfResolutionException.class);
        assertSameRequest(requests.stored(REQUEST_ID), before);
        assertThat(requests.saves).isZero();
        assertThat(grants.all()).isEmpty();
    }

    @Test
    void anotherAdminCanResolveAnAdminsRequest() {
        // Arrange
        requests.seed(pending(REQUEST_ID, ADMIN_USER));

        // Act
        approve.approve(OTHER_ADMIN, REQUEST_ID);

        // Assert
        assertThat(requests.stored(REQUEST_ID).status()).isEqualTo(APPLIED);
        assertThat(grants.all()).singleElement()
                .satisfies(grant -> assertThat(grant.userId()).isEqualTo(ADMIN_USER));
    }

    @Test
    void standardDisabledOrUnknownActorsCannotResolve() {
        // Arrange
        requests.seed(pending(REQUEST_ID, OTHER_STANDARD));
        users.put(OTHER_ADMIN, "ADMIN", "DISABLED");
        PermissionRequest before = requests.stored(REQUEST_ID);
        List<UUID> actors = List.of(STANDARD_USER, OTHER_ADMIN, UNKNOWN_USER);

        // Act
        List<Throwable> failures = new ArrayList<>();
        for (UUID actor : actors) {
            failures.add(catchThrowable(() -> approve.approve(actor, REQUEST_ID)));
            failures.add(catchThrowable(() -> reject.reject(actor, REQUEST_ID, REASON)));
        }

        // Assert
        assertThat(failures).hasSize(6)
                .allSatisfy(f -> assertThat(f).isInstanceOf(PermissionActorNotAuthorizedException.class));
        assertSameRequest(requests.stored(REQUEST_ID), before);
        assertThat(requests.locks).isZero();
        assertThat(grants.all()).isEmpty();
    }

    @Test
    void anAdminDemotedInTheDatabaseLosesResolutionImmediately() {
        // Arrange
        requests.seed(pending(REQUEST_ID, STANDARD_USER));
        users.put(ADMIN_USER, "STANDARD", "ACTIVE");

        // Act
        Throwable approval = catchThrowable(() -> approve.approve(ADMIN_USER, REQUEST_ID));
        Throwable rejection = catchThrowable(() -> reject.reject(ADMIN_USER, REQUEST_ID, REASON));

        // Assert
        assertThat(approval).isInstanceOf(PermissionActorNotAuthorizedException.class);
        assertThat(rejection).isInstanceOf(PermissionActorNotAuthorizedException.class);
        assertThat(requests.stored(REQUEST_ID).status()).isEqualTo(PENDING);
    }

    // ---- PermissionChecker ----

    @Test
    void theCheckerOnlyReadsGrantsAndIgnoresRoles() {
        // Arrange
        grants.save(new GrantedPermission(UUID.randomUUID(), STANDARD_USER, PURCHASE_ORDER_CREATE, REQUEST_ID, NOW));

        // Act
        boolean standardWithGrant = checker.hasPermission(STANDARD_USER, PURCHASE_ORDER_CREATE);
        boolean adminWithoutGrant = checker.hasPermission(ADMIN_USER, PURCHASE_ORDER_CREATE);

        // Assert
        assertThat(standardWithGrant).isTrue();
        assertThat(adminWithoutGrant).isFalse();
        assertThat(users.lookups).isZero();
    }

    // ---- utilidades ----

    private static PermissionRequest pending(UUID id, UUID requester) {
        return PermissionRequest.create(id, requester, PURCHASE_ORDER_CREATE, REQUESTED_AT);
    }

    private static void assertSameRequest(PermissionRequest actual, PermissionRequest expected) {
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.requestedBy()).isEqualTo(expected.requestedBy());
        assertThat(actual.permissionCode()).isEqualTo(expected.permissionCode());
        assertThat(actual.requestedAt()).isEqualTo(expected.requestedAt());
        assertThat(actual.decidedBy()).isEqualTo(expected.decidedBy());
        assertThat(actual.decidedAt()).isEqualTo(expected.decidedAt());
        assertThat(actual.rejectionReason()).isEqualTo(expected.rejectionReason());
        assertThat(actual.appliedBy()).isEqualTo(expected.appliedBy());
        assertThat(actual.appliedAt()).isEqualTo(expected.appliedAt());
    }

    private static PermissionRequest copy(PermissionRequest r) {
        return PermissionRequest.restore(r.id(), r.requestedBy(), r.permissionCode(), r.requestedAt(), r.status(),
                r.decidedBy().orElse(null), r.decidedAt().orElse(null), r.rejectionReason().orElse(null),
                r.appliedBy().orElse(null), r.appliedAt().orElse(null));
    }

    // ---- fakes ----

    /** Solo {@code find} es parte del contrato de estos casos de uso; el resto no debe usarse. */
    private static final class FakeUsers implements UserAdministrationRepository {

        private final Map<UUID, ManagedUser> users = new HashMap<>();
        private int lookups;

        void put(UUID id, String role, String status) {
            users.put(id, new ManagedUser(id, id + "@example.test", role, status, true));
        }

        @Override
        public Optional<ManagedUser> find(UUID userId) {
            lookups++;
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public void acquireAdminMembershipLock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UserSummary> page(int size, long offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countUsers() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ManagedUser> lock(UUID userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ManagedUser> lockByEmail(String emailNormalized) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countActiveAdmins() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countAdmins() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateRole(UUID userId, String role, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateStatus(UUID userId, String accountStatus, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void requirePasswordReset(UUID userId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertActiveAdmin(UUID id, String emailNormalized, String passwordHash, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void promoteToAdmin(UUID userId, String passwordHash, Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeRequests implements PermissionRequestRepository {

        private static final Comparator<PermissionRequest> ORDER =
                Comparator.comparing(PermissionRequest::requestedAt).thenComparing(PermissionRequest::id);

        private final Map<UUID, PermissionRequest> rows = new HashMap<>();
        private int saves;
        private int locks;
        private int globalListings;

        /** Prepara datos sin contar como guardado del caso de uso. */
        void seed(PermissionRequest request) {
            rows.put(request.id(), copy(request));
        }

        PermissionRequest stored(UUID id) {
            return copy(rows.get(id));
        }

        List<PermissionRequest> all() {
            return rows.values().stream().map(PermissionApplicationTest::copy).toList();
        }

        @Override
        public void save(PermissionRequest request) {
            saves++;
            rows.put(request.id(), copy(request));
        }

        @Override
        public Optional<PermissionRequest> findById(UUID id) {
            return Optional.ofNullable(rows.get(id)).map(PermissionApplicationTest::copy);
        }

        @Override
        public Optional<PermissionRequest> lockById(UUID id) {
            locks++;
            return findById(id);
        }

        @Override
        public List<PermissionRequest> findPending() {
            globalListings++;
            return rows.values().stream().filter(r -> r.status() == PENDING).sorted(ORDER)
                    .map(PermissionApplicationTest::copy).toList();
        }

        @Override
        public List<PermissionRequest> findPendingByRequester(UUID requesterId) {
            return rows.values().stream()
                    .filter(r -> r.status() == PENDING && r.requestedBy().equals(requesterId)).sorted(ORDER)
                    .map(PermissionApplicationTest::copy).toList();
        }
    }

    /** Reproduce las dos restricciones únicas de user_permissions. */
    private static final class FakeGrants implements UserPermissionRepository {

        private final List<GrantedPermission> rows = new ArrayList<>();

        List<GrantedPermission> all() {
            return List.copyOf(rows);
        }

        @Override
        public void save(GrantedPermission permission) {
            boolean duplicate = rows.stream().anyMatch(g -> g.sourceRequestId().equals(permission.sourceRequestId())
                    || (g.userId().equals(permission.userId())
                    && g.permissionCode() == permission.permissionCode()));
            if (duplicate) {
                throw new IllegalStateException("duplicate grant");
            }
            rows.add(permission);
        }

        @Override
        public Optional<GrantedPermission> findBySourceRequestId(UUID sourceRequestId) {
            return rows.stream().filter(g -> g.sourceRequestId().equals(sourceRequestId)).findFirst();
        }

        @Override
        public boolean existsByUserIdAndPermissionCode(UUID userId, PermissionCode permissionCode) {
            return rows.stream().anyMatch(g -> g.userId().equals(userId) && g.permissionCode() == permissionCode);
        }
    }
}
