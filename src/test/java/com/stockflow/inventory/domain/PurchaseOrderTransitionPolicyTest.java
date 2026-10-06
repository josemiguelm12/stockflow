package com.stockflow.inventory.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.APPROVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.CANCELLED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.DRAFT;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.RECEIVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseOrderTransitionPolicyTest {

    /** Tabla del contrato escrita a mano: si la política cambia, estas pruebas fallan. */
    private static final List<Arguments> PERMITTED = List.of(
            Arguments.of(DRAFT, SUBMITTED),
            Arguments.of(DRAFT, CANCELLED),
            Arguments.of(SUBMITTED, APPROVED),
            Arguments.of(SUBMITTED, CANCELLED),
            Arguments.of(APPROVED, RECEIVED),
            Arguments.of(APPROVED, CANCELLED));

    static Stream<Arguments> permitted() {
        return PERMITTED.stream();
    }

    static Stream<Arguments> forbidden() {
        List<Arguments> forbidden = new ArrayList<>();
        for (PurchaseOrderStatus from : PurchaseOrderStatus.values()) {
            for (PurchaseOrderStatus to : PurchaseOrderStatus.values()) {
                boolean listed = PERMITTED.stream()
                        .anyMatch(pair -> pair.get()[0] == from && pair.get()[1] == to);
                if (!listed) {
                    forbidden.add(Arguments.of(from, to));
                }
            }
        }
        return forbidden.stream();
    }

    @Test
    void thereAreExactlyTheFiveDeclaredStates() {
        assertThat(PurchaseOrderStatus.values())
                .containsExactly(DRAFT, SUBMITTED, APPROVED, RECEIVED, CANCELLED);
    }

    @ParameterizedTest
    @MethodSource("permitted")
    void permittedTransitionsAreAllowed(PurchaseOrderStatus from, PurchaseOrderStatus to) {
        assertThat(PurchaseOrderTransitionPolicy.isAllowed(from, to)).isTrue();
        assertThatNoException().isThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(from, to));
    }

    @ParameterizedTest
    @MethodSource("forbidden")
    void everyOtherPairIsRejectedByDefault(PurchaseOrderStatus from, PurchaseOrderStatus to) {
        assertThat(PurchaseOrderTransitionPolicy.isAllowed(from, to)).isFalse();
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(from, to))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class)
                .satisfies(e -> {
                    InvalidPurchaseOrderTransitionException invalid = (InvalidPurchaseOrderTransitionException) e;
                    assertThat(invalid.from()).isEqualTo(from);
                    assertThat(invalid.to()).isEqualTo(to);
                });
    }

    @Test
    void submittedCannotSkipApprovalToReceived() {
        assertThat(PurchaseOrderTransitionPolicy.isAllowed(SUBMITTED, RECEIVED)).isFalse();
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(SUBMITTED, RECEIVED))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class)
                .hasMessage("Purchase order transition not allowed: SUBMITTED -> RECEIVED");
    }

    @Test
    void namedForbiddenTransitionsAreRejected() {
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(RECEIVED, DRAFT))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(CANCELLED, SUBMITTED))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
    }

    @Test
    void onlyReceivedAndCancelledAreTerminal() {
        Set<PurchaseOrderStatus> terminal = EnumSet.noneOf(PurchaseOrderStatus.class);
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            if (PurchaseOrderTransitionPolicy.isTerminal(status)) {
                terminal.add(status);
            }
        }
        assertThat(terminal).containsExactlyInAnyOrder(RECEIVED, CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"RECEIVED", "CANCELLED"})
    void terminalStatesHaveNoExits(PurchaseOrderStatus terminal) {
        assertThat(PurchaseOrderTransitionPolicy.allowedTargets(terminal)).isEmpty();
        for (PurchaseOrderStatus target : PurchaseOrderStatus.values()) {
            assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(terminal, target))
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(PurchaseOrderStatus.class)
    void transitioningToTheSameStateIsRejected(PurchaseOrderStatus status) {
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(status, status))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(null, SUBMITTED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.requireAllowed(DRAFT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.allowedTargets(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.isTerminal(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allowedTargetsCannotBeModifiedFromOutside() {
        assertThatThrownBy(() -> PurchaseOrderTransitionPolicy.allowedTargets(DRAFT).add(RECEIVED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(PurchaseOrderTransitionPolicy.isAllowed(DRAFT, RECEIVED)).isFalse();
    }
}
