package com.stockflow.support;

import com.stockflow.notification.application.ActivationLinkBuilder;
import com.stockflow.notification.application.OutboundEmailRepository;
import com.stockflow.notification.application.OutboxDispatcher;
import com.stockflow.notification.application.OutboxPayloadCipher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;

@TestConfiguration
public class IntegrationTestBeans {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Bean
    RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }

    @Bean
    OutboxDispatcher testOutboxDispatcher(OutboundEmailRepository emails, OutboxPayloadCipher cipher,
                                          ActivationLinkBuilder links, RecordingEmailSender sender,
                                          PlatformTransactionManager transactionManager, Clock clock) {
        return new OutboxDispatcher(emails, cipher, links, sender, transactionManager, clock);
    }
}
