package com.stockflow.notification.application;

import org.springframework.stereotype.Component;

/**
 * Plantillas de correo por clave. Una clave desconocida lanza IllegalStateException: el dispatcher deja ese
 * correo PENDING en vez de enviar un mensaje que no sabe construir.
 */
@Component
public class EmailTemplates {

    public static final String ACTIVATION = "ACTIVATION";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";

    public record Rendered(String subject, String body) {

        @Override
        public String toString() {
            return "Rendered[redacted]";
        }
    }

    private final ActivationLinkBuilder activationLinks;
    private final PasswordResetLinkBuilder passwordResetLinks;

    EmailTemplates(ActivationLinkBuilder activationLinks, PasswordResetLinkBuilder passwordResetLinks) {
        this.activationLinks = activationLinks;
        this.passwordResetLinks = passwordResetLinks;
    }

    public Rendered render(String templateKey, String rawToken) {
        return switch (templateKey) {
            case ACTIVATION -> new Rendered("Active su cuenta de StockFlow",
                    "Para activar su cuenta de StockFlow abra este enlace:\n\n" + activationLinks.build(rawToken)
                            + "\n\nSi no solicitó este registro, ignore este mensaje.\n");
            case PASSWORD_RESET -> new Rendered("Restablezca su contraseña de StockFlow",
                    "Para restablecer su contraseña de StockFlow abra este enlace:\n\n" + passwordResetLinks.build(rawToken)
                            + "\n\nEl enlace es de un solo uso y vence pronto. Si no solicitó este cambio, ignore este mensaje:"
                            + " su contraseña actual no ha cambiado.\n");
            default -> throw new IllegalStateException("Unknown email template");
        };
    }
}
