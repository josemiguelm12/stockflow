package com.stockflow.notification.adapter.in.worker;

import com.stockflow.notification.adapter.out.smtp.SmtpEmailSender;
import com.stockflow.notification.application.ActivationLinkBuilder;
import com.stockflow.notification.application.OutboxDispatcher;
import com.stockflow.notification.application.OutboundEmailRepository;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.notification.application.EmailSender;
import com.stockflow.shared.config.SmtpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Properties;

/**
 * Worker SMTP: proceso separado de la API. Se activa solo con stockflow.worker.enabled=true, procesa un
 * lote y termina. Requiere las variables STOCKFLOW_SMTP_*; si faltan, no arranca.
 */
@Configuration
@ConditionalOnProperty(name = "stockflow.worker.enabled", havingValue = "true")
class OutboxWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorkerConfig.class);
    private static final int MAX_EMAILS_PER_RUN = 50;
    private static final String TIMEOUT_MS = "10000";

    @Bean
    JavaMailSender javaMailSender(SmtpProperties smtp) {
        if (isBlank(smtp.host()) || smtp.port() == null || smtp.port() <= 0 || isBlank(smtp.from())) {
            throw new IllegalStateException(
                    "The worker requires STOCKFLOW_SMTP_HOST, STOCKFLOW_SMTP_PORT and STOCKFLOW_SMTP_FROM");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        if (!isBlank(smtp.username())) {
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
            props.put("mail.smtp.auth", "true");
            if (smtp.port() == 465) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
        }
        return sender;
    }

    @Bean
    EmailSender smtpEmailSender(JavaMailSender javaMailSender, SmtpProperties smtp) {
        return new SmtpEmailSender(javaMailSender, smtp.from());
    }

    @Bean
    OutboxDispatcher outboxDispatcher(OutboundEmailRepository emails, OutboxPayloadCipher cipher,
                                      ActivationLinkBuilder links,
                                      EmailSender sender, PlatformTransactionManager transactionManager, Clock clock) {
        return new OutboxDispatcher(emails, cipher, links, sender, transactionManager, clock);
    }

    @Bean
    ApplicationRunner outboxWorkerRunner(OutboxDispatcher dispatcher) {
        return args -> {
            OutboxDispatcher.Result result = dispatcher.dispatchPending(MAX_EMAILS_PER_RUN);
            log.info("Outbox worker finished: sent={}, failed={}", result.sent(), result.failed());
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
