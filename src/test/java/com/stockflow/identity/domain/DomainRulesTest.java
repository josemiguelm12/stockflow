package com.stockflow.identity.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class DomainRulesTest {

    @Test
    void emailIsTrimmedAndLowercasedWithRootLocale() {
        assertThat(EmailNormalizer.normalize("  USER@Example.TEST \t")).isEqualTo("user@example.test");
        assertThat(EmailNormalizer.normalize("I@EXAMPLE.TEST")).isEqualTo("i@example.test");
    }

    @Test
    void emailFormatIsValidated() {
        assertThat(EmailNormalizer.isValid("user@example.test")).isTrue();
        assertThat(EmailNormalizer.isValid("not-an-email")).isFalse();
        assertThat(EmailNormalizer.isValid("a@b")).isFalse();
        assertThat(EmailNormalizer.isValid("two words@example.test")).isFalse();
        assertThat(EmailNormalizer.isValid("a".repeat(320) + "@example.test")).isFalse();
    }

    @Test
    void passwordPolicyRequiresLengthLetterAndDigit() {
        assertThat(PasswordPolicy.violation("abc12")).isPresent();
        assertThat(PasswordPolicy.violation("abcdefgh")).isPresent();
        assertThat(PasswordPolicy.violation("12345678")).isPresent();
        assertThat(PasswordPolicy.violation("a".repeat(70) + "12345")).isPresent();
        assertThat(PasswordPolicy.violation(null)).isPresent();
        assertThat(PasswordPolicy.violation("abcdefg1")).isEmpty();
    }

    @Test
    void tokensAreRandomAndOnlyTheHashIsDerivable() {
        SecureRandom random = new SecureRandom();
        ActivationToken first = ActivationToken.generate(random);
        ActivationToken second = ActivationToken.generate(random);

        assertThat(first.raw()).isNotEqualTo(second.raw()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(first.hash()).hasSize(64).isEqualTo(ActivationToken.hash(first.raw())).isNotEqualTo(first.raw());
        assertThat(first.toString()).doesNotContain(first.raw()).doesNotContain(first.hash());
    }
}
