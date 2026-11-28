package com.stockflow.notification.application;

import com.stockflow.shared.config.ActivationProperties;
import com.stockflow.shared.config.PasswordResetProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates(
            new ActivationLinkBuilder(new ActivationProperties("http://localhost:8080/activate", Duration.ofHours(24))),
            new PasswordResetLinkBuilder(new PasswordResetProperties("http://localhost:8080/reset-password", null)));

    @Test
    void activationTemplateKeepsItsLinkAndSubject() {
        var rendered = templates.render(EmailTemplates.ACTIVATION, "abc-DEF_123");

        assertThat(rendered.subject()).isEqualTo("Active su cuenta de StockFlow");
        assertThat(rendered.body()).contains("http://localhost:8080/activate#token=abc-DEF_123");
    }

    @Test
    void passwordResetTemplateLinksToTheResetLandingWithTheTokenOnlyInTheFragment() {
        var rendered = templates.render(EmailTemplates.PASSWORD_RESET, "abc-DEF_123");

        assertThat(rendered.subject()).contains("contraseña");
        assertThat(rendered.body()).contains("http://localhost:8080/reset-password#token=abc-DEF_123")
                .doesNotContain("?token").doesNotContain("/abc-DEF_123")
                .doesNotContain("/activate");
    }

    @Test
    void theTokenIsUrlEncodedInTheFragment() {
        assertThat(templates.render(EmailTemplates.PASSWORD_RESET, "a b+c").body())
                .contains("#token=a+b%2Bc");
    }

    @Test
    void anUnknownTemplateKeyIsRejectedInsteadOfSendingSomethingElse() {
        assertThatThrownBy(() -> templates.render("SOMETHING_ELSE", "token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("token");
        assertThat(templates.render(EmailTemplates.PASSWORD_RESET, "secret-token-value").toString())
                .doesNotContain("secret-token-value");
    }

    // ---- P5: correo funcional ----

    @Test
    void everyFunctionalNotificationTypeHasAFunctionalTemplateUsingItsTitleAndMessage() {
        for (com.stockflow.notification.domain.NotificationType type
                : com.stockflow.notification.domain.NotificationType.values()) {
            assertThat(templates.isFunctional(type.name())).as(type.name()).isTrue();

            var rendered = templates.renderFunctional(type.name(),
                    new FunctionalEmailPayload("Titulo del aviso", "Cuerpo del aviso. Motivo: literal"));

            assertThat(rendered.subject()).isEqualTo("Titulo del aviso");
            assertThat(rendered.body()).startsWith("Cuerpo del aviso. Motivo: literal")
                    .contains("preferencias de notificaciones");
        }
    }

    @Test
    void essentialTemplatesAreNotFunctionalSoThePreferenceNeverAppliesToThem() {
        assertThat(templates.isFunctional(EmailTemplates.ACTIVATION)).isFalse();
        assertThat(templates.isFunctional(EmailTemplates.PASSWORD_RESET)).isFalse();
        assertThat(templates.isFunctional("SOMETHING_ELSE")).isFalse();
    }

    @Test
    void aFunctionalRenderRejectsEssentialOrUnknownKeys() {
        var payload = new FunctionalEmailPayload("t", "m");

        assertThatThrownBy(() -> templates.renderFunctional(EmailTemplates.ACTIVATION, payload))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> templates.renderFunctional("SOMETHING_ELSE", payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payload.toString()).doesNotContain("m]").contains("redacted");
    }
}
