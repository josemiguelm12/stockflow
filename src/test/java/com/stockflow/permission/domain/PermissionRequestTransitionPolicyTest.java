package com.stockflow.permission.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static com.stockflow.permission.domain.PermissionRequestStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;

class PermissionRequestTransitionPolicyTest {

    /** Tabla del contrato A2-T01 escrita a mano: si la política cambia, estas pruebas fallan. */
    private static final List<Arguments> PERMITTED = List.of(
            Arguments.of(PENDING, APPROVED),
            Arguments.of(APPROVED, APPLIED),
            Arguments.of(PENDING, REJECTED));

    static Stream<Arguments> permitted() {
        return PERMITTED.stream();
    }

    static Stream<Arguments> forbidden() {
        List<Arguments> forbidden = new ArrayList<>();
        for (PermissionRequestStatus from : PermissionRequestStatus.values()) {
            for (PermissionRequestStatus to : PermissionRequestStatus.values()) {
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
    void thereAreExactlyTheFourDeclaredStates() {
        // Arrange
        // (nothing to prepare: the enum itself is the subject)

        // Act
        PermissionRequestStatus[] statuses = PermissionRequestStatus.values();

        // Assert
        assertThat(statuses).containsExactly(PENDING, APPROVED, APPLIED, REJECTED);
    }

    @Test
    void theForbiddenTableCoversEveryOtherPairOfTheEnum() {
        // Arrange
        int allPairs = PermissionRequestStatus.values().length * PermissionRequestStatus.values().length;

        // Act
        long forbiddenPairs = forbidden().count();

        // Assert
        assertThat(PERMITTED).hasSize(3);
        assertThat(forbiddenPairs).isEqualTo(allPairs - 3);
    }

    @ParameterizedTest
    @MethodSource("permitted")
    void permittedTransitionsAreAllowed(PermissionRequestStatus from, PermissionRequestStatus to) {
        // Arrange
        // (from and to come from the hand-written table)

        // Act
        boolean allowed = PermissionRequestTransitionPolicy.isAllowed(from, to);

        // Assert
        assertThat(allowed).isTrue();
        assertThatNoException().isThrownBy(() -> PermissionRequestTransitionPolicy.requireAllowed(from, to));
    }

    @ParameterizedTest
    @MethodSource("forbidden")
    void everyOtherPairIsRejectedWithOriginAndTarget(PermissionRequestStatus from, PermissionRequestStatus to) {
        // Arrange
        // (from and to are every pair absent from the hand-written table)

        // Act
        boolean allowed = PermissionRequestTransitionPolicy.isAllowed(from, to);
        Throwable thrown = catchThrowable(() -> PermissionRequestTransitionPolicy.requireAllowed(from, to));

        // Assert
        assertThat(allowed).isFalse();
        assertThat(thrown)
                .isInstanceOf(InvalidPermissionRequestTransitionException.class)
                .hasMessage("Permission request transition not allowed: " + from + " -> " + to)
                .satisfies(e -> {
                    InvalidPermissionRequestTransitionException invalid =
                            (InvalidPermissionRequestTransitionException) e;
                    assertThat(invalid.from()).isEqualTo(from);
                    assertThat(invalid.to()).isEqualTo(to);
                });
    }

    @Test
    void pendingCannotSkipApprovalToApplied() {
        // Arrange
        PermissionRequestStatus from = PENDING;
        PermissionRequestStatus to = APPLIED;

        // Act
        boolean allowed = PermissionRequestTransitionPolicy.isAllowed(from, to);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void anApprovedRequestCannotBeRejected() {
        // Arrange
        PermissionRequestStatus from = APPROVED;
        PermissionRequestStatus to = REJECTED;

        // Act
        boolean allowed = PermissionRequestTransitionPolicy.isAllowed(from, to);

        // Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PermissionRequestStatus.class, names = {"APPLIED", "REJECTED"})
    void terminalStatesHaveNoExits(PermissionRequestStatus terminal) {
        // Arrange
        PermissionRequestStatus[] targets = PermissionRequestStatus.values();

        // Act
        long allowedExits = Stream.of(targets)
                .filter(target -> PermissionRequestTransitionPolicy.isAllowed(terminal, target))
                .count();

        // Assert
        assertThat(allowedExits).isZero();
    }

    @ParameterizedTest
    @EnumSource(PermissionRequestStatus.class)
    void transitioningToTheSameStateIsRejected(PermissionRequestStatus status) {
        // Arrange
        // (status is every value of the enum)

        // Act
        boolean allowed = PermissionRequestTransitionPolicy.isAllowed(status, status);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void nullStatesAreRejectedWithAControlledDomainException() {
        // Arrange
        PermissionRequestStatus missing = null;

        // Act
        Throwable nullOrigin = catchThrowable(() -> PermissionRequestTransitionPolicy.requireAllowed(missing, APPROVED));
        Throwable nullTarget = catchThrowable(() -> PermissionRequestTransitionPolicy.requireAllowed(PENDING, missing));
        Throwable bothNull = catchThrowable(() -> PermissionRequestTransitionPolicy.isAllowed(missing, missing));

        // Assert
        assertThat(nullOrigin).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(nullTarget).isInstanceOf(InvalidPermissionRequestException.class);
        assertThat(bothNull).isInstanceOf(InvalidPermissionRequestException.class);
    }
}
