package com.stockflow.permission.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static com.stockflow.permission.domain.PermissionRequestStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PermissionRequestTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REQUESTER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID DECIDER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID APPLIER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID OTHER_ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final Instant BEFORE_REQUEST = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-01-01T11:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");
    private static final String REASON = "No business justification provided";
    private static final String OTHER_REASON = "Duplicate request";

    @Test
    void theCatalogDeclaresOnlyPurchaseOrderCreate() {
        // Arrange
        // (nothing to prepare: the enum itself is the subject)

        // Act
        PermissionCode[] codes = PermissionCode.values();

        // Assert
        assertThat(codes).containsExactly(PURCHASE_ORDER_CREATE);
    }

    @Test
    void aNewRequestStartsPendingWithoutDecisionOrApplication() {
        // Arrange
        // (fixed id, requester and instant declared as constants)

        // Act
        PermissionRequest request = PermissionRequest.create(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE,
                REQUESTED_AT);

        // Assert
        assertPendingWithoutMetadata(request);
    }

    @Test
    void creationRejectsEachMissingMandatoryAttribute() {
        // Arrange
        // (each call below omits exactly one mandatory attribute)

        // Act
        Throwable noId = catchThrowable(() ->
                PermissionRequest.create(null, REQUESTER, PURCHASE_ORDER_CREATE, REQUESTED_AT));
        Throwable noRequester = catchThrowable(() ->
                PermissionRequest.create(REQUEST_ID, null, PURCHASE_ORDER_CREATE, REQUESTED_AT));
        Throwable noCode = catchThrowable(() ->
                PermissionRequest.create(REQUEST_ID, REQUESTER, null, REQUESTED_AT));
        Throwable noRequestedAt = catchThrowable(() ->
                PermissionRequest.create(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE, null));

        // Assert
        assertThat(noId).isInstanceOf(InvalidPermissionRequestException.class).hasMessage("id is required");
        assertThat(noRequester).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("requestedBy is required");
        assertThat(noCode).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("permissionCode is required");
        assertThat(noRequestedAt).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("requestedAt is required");
    }

    @Test
    void approvingAPendingRequestRecordsWhoAndWhenItWasApproved() {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        request.approve(DECIDER, DECIDED_AT);

        // Assert
        assertApprovedBy(request, DECIDER, DECIDED_AT);
    }

    @Test
    void rejectingAPendingRequestRecordsWhoWhenAndWhyItWasRejected() {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        request.reject(DECIDER, DECIDED_AT, REASON);

        // Assert
        assertRejectedBy(request, DECIDER, DECIDED_AT, REASON);
    }

    @Test
    void applyingAnApprovedRequestRecordsApplicationAndKeepsTheApproval() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);

        // Act
        request.apply(APPLIER, APPLIED_AT);

        // Assert
        assertAppliedBy(request, APPLIER, APPLIED_AT);
    }

    @Test
    void applyingAPendingRequestIsRejectedAndDoesNotChangeTheRequest() {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        Throwable thrown = catchThrowable(() -> request.apply(APPLIER, APPLIED_AT));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        InvalidPermissionRequestTransitionException invalid = (InvalidPermissionRequestTransitionException) thrown;
        assertThat(invalid.from()).isEqualTo(PENDING);
        assertThat(invalid.to()).isEqualTo(APPLIED);
        assertThat(invalid).hasMessage("Permission request transition not allowed: PENDING -> APPLIED");
        assertPendingWithoutMetadata(request);
    }

    @Test
    void rejectingAnApprovedRequestIsRejectedAndKeepsTheApproval() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);

        // Act
        Throwable thrown = catchThrowable(() -> request.reject(OTHER_ACTOR, LATER, REASON));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertApprovedBy(request, DECIDER, DECIDED_AT);
    }

    @Test
    void approvingTwiceIsRejectedAndNeverReplacesTheRecordedDecision() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);

        // Act
        Throwable thrown = catchThrowable(() -> request.approve(OTHER_ACTOR, LATER));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertApprovedBy(request, DECIDER, DECIDED_AT);
    }

    @Test
    void aRejectedRequestCannotBeApprovedRejectedOrAppliedAndKeepsItsReason() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.reject(DECIDER, DECIDED_AT, REASON);

        // Act
        Throwable approve = catchThrowable(() -> request.approve(OTHER_ACTOR, LATER));
        Throwable reject = catchThrowable(() -> request.reject(OTHER_ACTOR, LATER, OTHER_REASON));
        Throwable apply = catchThrowable(() -> request.apply(OTHER_ACTOR, LATER));

        // Assert
        assertThat(approve).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(reject).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(apply).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertRejectedBy(request, DECIDER, DECIDED_AT, REASON);
    }

    @Test
    void anAppliedRequestCannotBeApprovedRejectedOrAppliedAgain() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);
        request.apply(APPLIER, APPLIED_AT);

        // Act
        Throwable approve = catchThrowable(() -> request.approve(OTHER_ACTOR, LATER));
        Throwable reject = catchThrowable(() -> request.reject(OTHER_ACTOR, LATER, REASON));
        Throwable apply = catchThrowable(() -> request.apply(OTHER_ACTOR, LATER));

        // Assert
        assertThat(approve).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(reject).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(apply).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertAppliedBy(request, APPLIER, APPLIED_AT);
    }

    @Test
    void decidingWithoutActorOrInstantIsRejectedAndDoesNotChangeTheRequest() {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        Throwable approveWithoutActor = catchThrowable(() -> request.approve(null, DECIDED_AT));
        Throwable approveWithoutInstant = catchThrowable(() -> request.approve(DECIDER, null));
        Throwable rejectWithoutActor = catchThrowable(() -> request.reject(null, DECIDED_AT, REASON));
        Throwable rejectWithoutInstant = catchThrowable(() -> request.reject(DECIDER, null, REASON));

        // Assert
        assertThat(approveWithoutActor).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(approveWithoutInstant).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(rejectWithoutActor).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(rejectWithoutInstant).isInstanceOf(InvalidPermissionRequestException.class);
        assertPendingWithoutMetadata(request);
    }

    @Test
    void applyingWithoutActorOrInstantIsRejectedAndDoesNotChangeTheRequest() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);

        // Act
        Throwable withoutActor = catchThrowable(() -> request.apply(null, APPLIED_AT));
        Throwable withoutInstant = catchThrowable(() -> request.apply(APPLIER, null));

        // Assert
        assertThat(withoutActor).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutInstant).isInstanceOf(InvalidPermissionRequestException.class);
        assertApprovedBy(request, DECIDER, DECIDED_AT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", " \t\n "})
    void rejectingWithoutAWrittenReasonIsRefusedAndLeavesTheRequestIntact(String invalidReason) {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        Throwable thrown = catchThrowable(() -> request.reject(DECIDER, DECIDED_AT, invalidReason));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("rejectionReason is required");
        assertPendingWithoutMetadata(request);
    }

    @Test
    void aDecisionMayHappenExactlyWhenTheRequestWasMade() {
        // Arrange
        PermissionRequest toApprove = newPendingRequest();
        PermissionRequest toReject = newPendingRequest();

        // Act
        toApprove.approve(DECIDER, REQUESTED_AT);
        toReject.reject(DECIDER, REQUESTED_AT, REASON);

        // Assert
        assertApprovedBy(toApprove, DECIDER, REQUESTED_AT);
        assertRejectedBy(toReject, DECIDER, REQUESTED_AT, REASON);
    }

    @Test
    void approvingBeforeTheRequestWasMadeIsRefusedAndLeavesTheRequestIntact() {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        Throwable thrown = catchThrowable(() -> request.approve(DECIDER, BEFORE_REQUEST));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("decidedAt must not be before requestedAt");
        assertPendingWithoutMetadata(request);
    }

    @Test
    void rejectingBeforeTheRequestWasMadeIsRefusedAndLeavesTheRequestIntact() {
        // Arrange
        PermissionRequest request = newPendingRequest();

        // Act
        Throwable thrown = catchThrowable(() -> request.reject(DECIDER, BEFORE_REQUEST, REASON));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("decidedAt must not be before requestedAt");
        assertPendingWithoutMetadata(request);
    }

    @Test
    void anApplicationMayHappenExactlyWhenTheRequestWasApproved() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);

        // Act
        request.apply(APPLIER, DECIDED_AT);

        // Assert
        assertAppliedBy(request, APPLIER, DECIDED_AT);
    }

    @Test
    void applyingBeforeTheApprovalIsRefusedAndKeepsTheApproval() {
        // Arrange
        PermissionRequest request = newPendingRequest();
        request.approve(DECIDER, DECIDED_AT);

        // Act
        Throwable thrown = catchThrowable(() -> request.apply(APPLIER, REQUESTED_AT));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("appliedAt must not be before decidedAt");
        assertApprovedBy(request, DECIDER, DECIDED_AT);
    }

    @Test
    void restoringAConsistentAppliedRequestKeepsAllItsMetadata() {
        // Arrange
        // (complete approval and application metadata declared as constants)

        // Act
        PermissionRequest request = restore(APPLIED, DECIDER, DECIDED_AT, null, APPLIER, APPLIED_AT);

        // Assert
        assertAppliedBy(request, APPLIER, APPLIED_AT);
    }

    @Test
    void restoringAConsistentRejectedRequestKeepsItsReason() {
        // Arrange
        // (complete rejection metadata declared as constants)

        // Act
        PermissionRequest request = restore(REJECTED, DECIDER, DECIDED_AT, REASON, null, null);

        // Assert
        assertRejectedBy(request, DECIDER, DECIDED_AT, REASON);
    }

    @Test
    void restoringAcceptsInstantsThatCoincide() {
        // Arrange
        // (decision at requestedAt and application at decidedAt)

        // Act
        PermissionRequest request = restore(APPLIED, DECIDER, REQUESTED_AT, null, APPLIER, REQUESTED_AT);

        // Assert
        assertThat(request.decidedAt()).contains(REQUESTED_AT);
        assertThat(request.appliedAt()).contains(REQUESTED_AT);
    }

    @Test
    void restoringRejectsMissingStatusAndMandatoryAttributes() {
        // Arrange
        // (each call below omits exactly one mandatory value)

        // Act
        Throwable noStatus = catchThrowable(() -> restore(null, null, null, null, null, null));
        Throwable noId = catchThrowable(() -> PermissionRequest.restore(null, REQUESTER, PURCHASE_ORDER_CREATE,
                REQUESTED_AT, PENDING, null, null, null, null, null));

        // Assert
        assertThat(noStatus).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("status is required");
        assertThat(noId).isInstanceOf(InvalidPermissionRequestException.class).hasMessage("id is required");
    }

    @Test
    void pendingCannotCarryDecisionApplicationOrReason() {
        // Arrange
        // (PENDING combined with each kind of metadata)

        // Act
        Throwable withDecision = catchThrowable(() -> restore(PENDING, DECIDER, DECIDED_AT, null, null, null));
        Throwable withReason = catchThrowable(() -> restore(PENDING, null, null, REASON, null, null));
        Throwable withApplication = catchThrowable(() -> restore(PENDING, null, null, null, APPLIER, APPLIED_AT));

        // Assert
        assertThat(withDecision).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withReason).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withApplication).isInstanceOf(InvalidPermissionRequestException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PermissionRequestStatus.class, names = {"APPROVED", "REJECTED"})
    void decidedStatesRequireCompleteDecisionAndNoApplication(PermissionRequestStatus decided) {
        // Arrange
        String reason = decided == REJECTED ? REASON : null;

        // Act
        Throwable withoutDecision = catchThrowable(() -> restore(decided, null, null, reason, null, null));
        Throwable withoutDecider = catchThrowable(() -> restore(decided, null, DECIDED_AT, reason, null, null));
        Throwable withoutDecisionInstant = catchThrowable(() -> restore(decided, DECIDER, null, reason, null, null));
        Throwable withApplication = catchThrowable(() ->
                restore(decided, DECIDER, DECIDED_AT, reason, APPLIER, APPLIED_AT));

        // Assert
        assertThat(withoutDecision).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutDecider).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutDecisionInstant).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withApplication).isInstanceOf(InvalidPermissionRequestException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", " \t\n "})
    void restoringARejectedRequestRequiresAWrittenReason(String invalidReason) {
        // Arrange
        // (complete rejection metadata except for the reason)

        // Act
        Throwable thrown = catchThrowable(() ->
                restore(REJECTED, DECIDER, DECIDED_AT, invalidReason, null, null));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class);
    }

    @Test
    void statesOtherThanRejectedCannotCarryAReason() {
        // Arrange
        // (otherwise consistent APPROVED and APPLIED metadata plus a reason)

        // Act
        Throwable approved = catchThrowable(() -> restore(APPROVED, DECIDER, DECIDED_AT, REASON, null, null));
        Throwable approvedBlank = catchThrowable(() -> restore(APPROVED, DECIDER, DECIDED_AT, "", null, null));
        Throwable applied = catchThrowable(() ->
                restore(APPLIED, DECIDER, DECIDED_AT, REASON, APPLIER, APPLIED_AT));

        // Assert
        assertThat(approved).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(approvedBlank).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(applied).isInstanceOf(InvalidPermissionRequestException.class);
    }

    @Test
    void appliedRequiresCompleteApprovalAndApplicationMetadata() {
        // Arrange
        // (APPLIED combined with incomplete metadata)

        // Act
        Throwable withoutApproval = catchThrowable(() -> restore(APPLIED, null, null, null, APPLIER, APPLIED_AT));
        Throwable withoutApplication = catchThrowable(() -> restore(APPLIED, DECIDER, DECIDED_AT, null, null, null));
        Throwable withoutApplier = catchThrowable(() ->
                restore(APPLIED, DECIDER, DECIDED_AT, null, null, APPLIED_AT));
        Throwable withoutApplicationInstant = catchThrowable(() ->
                restore(APPLIED, DECIDER, DECIDED_AT, null, APPLIER, null));

        // Assert
        assertThat(withoutApproval).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutApplication).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutApplier).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(withoutApplicationInstant).isInstanceOf(InvalidPermissionRequestException.class);
    }

    @Test
    void restoringRejectsADecisionBeforeTheRequest() {
        // Arrange
        // (each decided state with decidedAt before requestedAt)

        // Act
        Throwable approved = catchThrowable(() -> restore(APPROVED, DECIDER, BEFORE_REQUEST, null, null, null));
        Throwable rejected = catchThrowable(() -> restore(REJECTED, DECIDER, BEFORE_REQUEST, REASON, null, null));
        Throwable applied = catchThrowable(() ->
                restore(APPLIED, DECIDER, BEFORE_REQUEST, null, APPLIER, APPLIED_AT));

        // Assert
        assertThat(approved).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("decidedAt must not be before requestedAt");
        assertThat(rejected).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("decidedAt must not be before requestedAt");
        assertThat(applied).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("decidedAt must not be before requestedAt");
    }

    @Test
    void restoringRejectsAnApplicationBeforeTheApproval() {
        // Arrange
        // (appliedAt between requestedAt and decidedAt)

        // Act
        Throwable thrown = catchThrowable(() ->
                restore(APPLIED, DECIDER, APPLIED_AT, null, APPLIER, DECIDED_AT));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPermissionRequestException.class)
                .hasMessage("appliedAt must not be before decidedAt");
    }

    private static PermissionRequest newPendingRequest() {
        return PermissionRequest.create(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUESTED_AT);
    }

    private static PermissionRequest restore(PermissionRequestStatus status, UUID decidedBy, Instant decidedAt,
                                             String rejectionReason, UUID appliedBy, Instant appliedAt) {
        return PermissionRequest.restore(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUESTED_AT, status,
                decidedBy, decidedAt, rejectionReason, appliedBy, appliedAt);
    }

    private static void assertRequestIdentity(PermissionRequest request) {
        assertThat(request.id()).isEqualTo(REQUEST_ID);
        assertThat(request.requestedBy()).isEqualTo(REQUESTER);
        assertThat(request.permissionCode()).isEqualTo(PURCHASE_ORDER_CREATE);
        assertThat(request.requestedAt()).isEqualTo(REQUESTED_AT);
    }

    private static void assertPendingWithoutMetadata(PermissionRequest request) {
        assertRequestIdentity(request);
        assertThat(request.status()).isEqualTo(PENDING);
        assertThat(request.decidedBy()).isEmpty();
        assertThat(request.decidedAt()).isEmpty();
        assertThat(request.rejectionReason()).isEmpty();
        assertThat(request.appliedBy()).isEmpty();
        assertThat(request.appliedAt()).isEmpty();
    }

    private static void assertApprovedBy(PermissionRequest request, UUID decidedBy, Instant decidedAt) {
        assertRequestIdentity(request);
        assertThat(request.status()).isEqualTo(APPROVED);
        assertThat(request.decidedBy()).contains(decidedBy);
        assertThat(request.decidedAt()).contains(decidedAt);
        assertThat(request.rejectionReason()).isEmpty();
        assertThat(request.appliedBy()).isEmpty();
        assertThat(request.appliedAt()).isEmpty();
    }

    private static void assertRejectedBy(PermissionRequest request, UUID decidedBy, Instant decidedAt,
                                         String reason) {
        assertRequestIdentity(request);
        assertThat(request.status()).isEqualTo(REJECTED);
        assertThat(request.decidedBy()).contains(decidedBy);
        assertThat(request.decidedAt()).contains(decidedAt);
        assertThat(request.rejectionReason()).contains(reason);
        assertThat(request.appliedBy()).isEmpty();
        assertThat(request.appliedAt()).isEmpty();
    }

    /** La aplicación conserva siempre la aprobación original de {@code DECIDER} en {@code DECIDED_AT}. */
    private static void assertAppliedBy(PermissionRequest request, UUID appliedBy, Instant appliedAt) {
        assertRequestIdentity(request);
        assertThat(request.status()).isEqualTo(APPLIED);
        assertThat(request.decidedBy()).contains(DECIDER);
        assertThat(request.decidedAt()).contains(DECIDED_AT);
        assertThat(request.rejectionReason()).isEmpty();
        assertThat(request.appliedBy()).contains(appliedBy);
        assertThat(request.appliedAt()).contains(appliedAt);
    }
}
