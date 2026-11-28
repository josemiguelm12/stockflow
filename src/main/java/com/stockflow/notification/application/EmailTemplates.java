package com.stockflow.notification.application;

import com.stockflow.notification.domain.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final Set<String> FUNCTIONAL_KEYS = Arrays.stream(NotificationType.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private static final String FUNCTIONAL_FOOTER =
            "\n\nPuede desactivar los avisos por correo desde sus preferencias de notificaciones en StockFlow.\n";

    /** Claves de los avisos funcionales (preferencia de usuario); activación y recuperación son esenciales. */
    public boolean isFunctional(String templateKey) {
        return FUNCTIONAL_KEYS.contains(templateKey);
    }

    Rendered renderFunctional(String templateKey, FunctionalEmailPayload payload) {
        if (!isFunctional(templateKey)) {
            throw new IllegalStateException("Unknown email template");
        }
        return new Rendered(payload.title(), payload.message() + FUNCTIONAL_FOOTER);
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
