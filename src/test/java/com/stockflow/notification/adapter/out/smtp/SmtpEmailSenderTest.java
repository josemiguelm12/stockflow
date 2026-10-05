package com.stockflow.notification.adapter.out.smtp;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.stockflow.notification.application.EmailDeliveryException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpEmailSenderTest {

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

    private static JavaMailSenderImpl mailSender(int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        return sender;
    }

    @Test
    void deliversToARealSmtpServer() throws Exception {
        new SmtpEmailSender(mailSender(SMTP.getSmtp().getPort()), "noreply@stockflow.test")
                .send("user@example.test", "Asunto", "Cuerpo del mensaje");

        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("user@example.test");
        assertThat(received[0].getFrom()[0].toString()).isEqualTo("noreply@stockflow.test");
        assertThat(received[0].getSubject()).isEqualTo("Asunto");
    }

    @Test
    void reportsAnUnreachableServerAsDeliveryException() {
        SmtpEmailSender sender = new SmtpEmailSender(mailSender(1), "noreply@stockflow.test");

        assertThatThrownBy(() -> sender.send("user@example.test", "Asunto", "Cuerpo"))
                .isInstanceOf(EmailDeliveryException.class);
    }
}
