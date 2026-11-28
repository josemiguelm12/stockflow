package com.stockflow.notification.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EmailRetryPolicyTest {

    @Test
    void aFirstFailureWaitsOneMinuteAndASecondWaitsFiveMore() {
        assertThat(EmailRetryPolicy.delayAfterFailure(1)).contains(Duration.ofMinutes(1));
        assertThat(EmailRetryPolicy.delayAfterFailure(2)).contains(Duration.ofMinutes(5));
    }

    @Test
    void theThirdFailureIsTerminalAndNoMoreAttemptsAreEverScheduled() {
        assertThat(EmailRetryPolicy.MAX_ATTEMPTS).isEqualTo(3);
        assertThat(EmailRetryPolicy.delayAfterFailure(3)).isEmpty();
        assertThat(EmailRetryPolicy.delayAfterFailure(4)).isEmpty();
    }

    @Test
    void anInvalidAttemptCountSchedulesNothing() {
        assertThat(EmailRetryPolicy.delayAfterFailure(0)).isEmpty();
        assertThat(EmailRetryPolicy.delayAfterFailure(-1)).isEmpty();
    }

    @Test
    void theRecordedErrorNeverCarriesTheExceptionMessage() {
        var smtp = new EmailDeliveryException("to=a@b.test token=SECRET password=hunter2",
                new IllegalStateException("smtp.internal:25 user=secret"));
        var other = new IllegalArgumentException("link=https://x/activate#token=SECRET");

        assertThat(OutboxDispatcher.describe(smtp)).isEqualTo("SMTP delivery failed (IllegalStateException)")
                .doesNotContain("SECRET").doesNotContain("hunter2").doesNotContain("a@b.test");
        assertThat(OutboxDispatcher.describe(other)).isEqualTo("Email processing failed (IllegalArgumentException)")
                .doesNotContain("SECRET");
        assertThat(OutboxDispatcher.describe(new EmailDeliveryException("x", null))).isEqualTo("SMTP delivery failed");
    }
}
