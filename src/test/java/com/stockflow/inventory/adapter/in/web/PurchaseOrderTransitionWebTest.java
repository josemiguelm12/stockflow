package com.stockflow.inventory.adapter.in.web;

import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.inventory.application.ApprovePurchaseOrder;
import com.stockflow.inventory.application.CancelPurchaseOrder;
import com.stockflow.inventory.application.PurchaseOrderActorNotAuthorizedException;
import com.stockflow.inventory.application.PurchaseOrderNotFoundException;
import com.stockflow.inventory.application.ReceivePurchaseOrder;
import com.stockflow.inventory.application.SubmitPurchaseOrder;
import com.stockflow.inventory.domain.InvalidPurchaseOrderTransitionException;
import com.stockflow.inventory.domain.PurchaseOrderSupplierRequiredException;
import com.stockflow.shared.config.SecurityConfig;
import com.stockflow.support.WebTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.APPROVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.RECEIVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P3-F1: rutas de transición; sesión, rol por ruta, actor del principal y errores 400/401/403/404/409 seguros. */
@WebMvcTest(controllers = PurchaseOrderTransitionController.class)
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000"
})
class PurchaseOrderTransitionWebTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000003a1");
    private static final UUID STANDARD_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000003b2");
    private static final UUID ORDER_ID = UUID.fromString("ffffffff-0000-0000-0000-0000000003f6");
    private static final String ORDERS = "/api/v1/purchase-orders/";
    private static final List<String> ACTIONS = List.of("submit", "approve", "receive", "cancel");
    private static final String FORGED_BODY = "{\"userId\":\"" + ADMIN_ID
            + "\",\"role\":\"ADMIN\",\"status\":\"RECEIVED\",\"supplierId\":\"" + UUID.randomUUID() + "\"}";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private SubmitPurchaseOrder submitPurchaseOrder;
    @MockitoBean
    private ApprovePurchaseOrder approvePurchaseOrder;
    @MockitoBean
    private ReceivePurchaseOrder receivePurchaseOrder;
    @MockitoBean
    private CancelPurchaseOrder cancelPurchaseOrder;
    @MockitoBean
    private AuthenticateSession authenticateSession;

    @BeforeEach
    void sessions() {
        when(authenticateSession.authenticate("admin-token")).thenReturn(Optional.of(
                new AuthenticatedUser(ADMIN_ID, "admin@example.test", "ADMIN", UUID.randomUUID())));
        when(authenticateSession.authenticate("standard-token")).thenReturn(Optional.of(
                new AuthenticatedUser(STANDARD_ID, "user@example.test", "STANDARD", UUID.randomUUID())));
        when(authenticateSession.authenticate("bad")).thenReturn(Optional.empty());
    }

    private static MockHttpServletRequestBuilder action(String action, String token) {
        MockHttpServletRequestBuilder request = post(ORDERS + ORDER_ID + "/" + action);
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private static void assertSafeProblem(ResultActions result, String... absent) throws Exception {
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        String body = result.andReturn().getResponse().getContentAsString();
        // "instance" repite la ruta pedida; lo que no debe aparecer son trazas, SQL ni detalles internos.
        assertThat(body).doesNotContain("Exception").doesNotContainIgnoringCase("select ");
        for (String text : absent) {
            assertThat(body).doesNotContain(text);
        }
    }

    private void verifyNoUseCaseWasCalled() {
        verifyNoInteractions(submitPurchaseOrder, approvePurchaseOrder, receivePurchaseOrder, cancelPurchaseOrder);
    }

    @Test
    void everyTransitionIs401WithoutAValidBearer() throws Exception {
        // Arrange
        String[] tokens = {null, "bad"};

        for (String action : ACTIONS) {
            for (String token : tokens) {
                // Act
                ResultActions result = mvc.perform(action(action, token));

                // Assert
                result.andExpect(status().isUnauthorized());
                assertSafeProblem(result);
            }
        }
        verifyNoUseCaseWasCalled();
    }

    @Test
    void aStandardIs403OnApproveAndReceiveBeforeReachingTheUseCase() throws Exception {
        for (String action : List.of("approve", "receive")) {
            // Act
            ResultActions result = mvc.perform(action(action, "standard-token"));

            // Assert
            result.andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Forbidden"));
            assertSafeProblem(result);
        }
        verifyNoUseCaseWasCalled();
    }

    @Test
    void aStandardSubmitsAndCancelsAsTheBearerPrincipal() throws Exception {
        // Act
        mvc.perform(action("submit", "standard-token").contentType(MediaType.APPLICATION_JSON).content(FORGED_BODY))
                .andExpect(status().isNoContent());
        mvc.perform(action("cancel", "standard-token").contentType(MediaType.APPLICATION_JSON).content(FORGED_BODY))
                .andExpect(status().isNoContent());

        // Assert
        verify(submitPurchaseOrder).submit(STANDARD_ID, ORDER_ID);
        verify(cancelPurchaseOrder).cancel(STANDARD_ID, ORDER_ID);
        verifyNoMoreInteractions(submitPurchaseOrder, cancelPurchaseOrder);
        verifyNoInteractions(approvePurchaseOrder, receivePurchaseOrder);
    }

    @Test
    void anAdminReachesEveryTransitionAsTheBearerPrincipal() throws Exception {
        for (String action : ACTIONS) {
            // Act / Assert
            mvc.perform(action(action, "admin-token").contentType(MediaType.APPLICATION_JSON).content(FORGED_BODY))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }

        // Assert
        verify(submitPurchaseOrder).submit(ADMIN_ID, ORDER_ID);
        verify(approvePurchaseOrder).approve(ADMIN_ID, ORDER_ID);
        verify(receivePurchaseOrder).receive(ADMIN_ID, ORDER_ID);
        verify(cancelPurchaseOrder).cancel(ADMIN_ID, ORDER_ID);
        verifyNoMoreInteractions(submitPurchaseOrder, approvePurchaseOrder, receivePurchaseOrder, cancelPurchaseOrder);
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        // Arrange
        doThrow(new PurchaseOrderNotFoundException()).when(approvePurchaseOrder).approve(any(), any());

        // Act
        ResultActions result = mvc.perform(action("approve", "admin-token"));

        // Assert
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        assertSafeProblem(result);
    }

    @Test
    void aForbiddenTransitionIs409WithoutRevealingStates() throws Exception {
        // Arrange
        doThrow(new InvalidPurchaseOrderTransitionException(SUBMITTED, RECEIVED))
                .when(receivePurchaseOrder).receive(any(), any());

        // Act
        ResultActions result = mvc.perform(action("receive", "admin-token"));

        // Assert
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Conflict"));
        assertSafeProblem(result, "SUBMITTED", "RECEIVED", APPROVED.name());
    }

    /** Equivalente MVC del detector P3-3: el envío sin proveedor es 409 genérico. */
    @Test
    void submittingWithoutSupplierIs409() throws Exception {
        // Arrange
        doThrow(new PurchaseOrderSupplierRequiredException()).when(submitPurchaseOrder).submit(any(), any());

        // Act
        ResultActions result = mvc.perform(action("submit", "standard-token"));

        // Assert
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Conflict"));
        assertSafeProblem(result, "supplier");
        verify(submitPurchaseOrder).submit(STANDARD_ID, ORDER_ID);
    }

    @Test
    void aStandardCancellingAnOrderThatLeftDraftIs403FromTheUseCase() throws Exception {
        // Arrange
        doThrow(new PurchaseOrderActorNotAuthorizedException()).when(cancelPurchaseOrder).cancel(any(), any());

        // Act
        ResultActions result = mvc.perform(action("cancel", "standard-token"));

        // Assert
        result.andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Forbidden"));
        assertSafeProblem(result, "ADMIN", "STANDARD");
        verify(cancelPurchaseOrder).cancel(STANDARD_ID, ORDER_ID);
    }

    @Test
    void aMalformedOrderIdIs400WithoutCallingTheUseCase() throws Exception {
        for (String action : ACTIONS) {
            // Act
            ResultActions result = mvc.perform(post(ORDERS + "not-a-uuid/" + action)
                    .header("Authorization", "Bearer admin-token"));

            // Assert
            result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Invalid request"))
                    .andExpect(jsonPath("$.detail").doesNotExist());
            assertSafeProblem(result, "UUID");
        }
        verifyNoUseCaseWasCalled();
    }

    @Test
    void otherMethodsAndActionsStayClosed() throws Exception {
        // Act / Assert
        mvc.perform(get(ORDERS + ORDER_ID + "/submit").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ORDERS + ORDER_ID + "/reopen").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ORDERS + ORDER_ID + "/approve/extra").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        verifyNoUseCaseWasCalled();
    }
}
