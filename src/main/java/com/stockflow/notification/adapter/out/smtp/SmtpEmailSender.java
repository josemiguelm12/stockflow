package com.stockflow.notification.adapter.out.smtp;

import com.stockflow.notification.application.EmailDeliveryException;
import com.stockflow.notification.application.EmailSender;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String recipient, String subject, String textBody) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(textBody);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw new EmailDeliveryException("SMTP delivery failed", e);
        }
    }
}
