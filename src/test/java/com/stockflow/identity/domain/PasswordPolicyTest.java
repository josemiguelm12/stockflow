package com.stockflow.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void acceptsPasswordThatMeetsAllPolicyRequirements() {
        // Arrange
        String password = "Clave12345";

        // Act
        var violation = PasswordPolicy.violation(password);

        // Assert
        assertThat(violation).isEmpty();
    }

    @Test
    void acceptsPasswordWithExactlyEightCharacters() {
        // Arrange
        String password = "Abcd1234";

        // Act
        var violation = PasswordPolicy.violation(password);

        // Assert
        assertThat(violation).isEmpty();
    }

    @Test
    void rejectsPasswordShorterThanMinimumLengthWithLengthViolation() {
        // Arrange
        String password = "Abc1234";

        // Act
        var violation = PasswordPolicy.violation(password);

        // Assert
        assertThat(violation).contains("must have at least 8 characters");
    }
}
