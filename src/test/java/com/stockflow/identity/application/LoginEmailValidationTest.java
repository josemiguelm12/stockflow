package com.stockflow.identity.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** T02-H02: el formato del email se valida antes de cualquier consulta. */
class LoginEmailValidationTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final AccessTokenIssuer issuer = mock(AccessTokenIssuer.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final Login login = new Login(users, sessions, issuer, hasher,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void malformedEmailsAreRejectedBeforeTouchingTheDatabase() {
        for (String bad : new String[]{"not-an-email", "a@b", "two words@example.test", "@example.test", "user@", "  "}) {
            assertThatThrownBy(() -> login.login(bad, "Passw0rd-secret"))
                    .as(bad)
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(((InvalidInputException) e).field()).isEqualTo("email"))
                    .hasMessageNotContaining(bad.trim().isEmpty() ? "\u0000" : bad.trim());
        }
        verifyNoInteractions(users, sessions, issuer);
    }

    @Test
    void aWellFormedUnknownEmailStillGetsTheGenericRejection() {
        when(users.lockForLogin(any())).thenReturn(Optional.empty());

        LoginResult result = login.login("  Nobody@Example.TEST ", "Passw0rd-secret");

        assertThat(result).isInstanceOf(LoginResult.InvalidCredentials.class);
        verify(users).lockForLogin("nobody@example.test");
    }
}
