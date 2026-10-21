package com.stockflow.inventory.adapter.in.web;

import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.inventory.application.CreatePurchaseOrder;
import com.stockflow.inventory.application.PurchaseOrderPermissionDeniedException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A2-T04 / RF-GP-05: la ruta solo exige Bearer; el permiso concedido lo decide el caso de uso, no el rol. */
@WebMvcTest(controllers = PurchaseOrderController.class)
@Import({SecurityConfig.class, WebTestClock.class})
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.rate-limit.global-per-minute=100000"
})
class PurchaseOrderWebTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    private static final UUID STANDARD_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b2");
    private static final UUID SUPPLIER = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000e5");
    private static final UUID ORDER_ID = UUID.fromString("ffffffff-0000-0000-0000-0000000000f6");
    private static final String ORDERS = "/api/v1/purchase-orders";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private CreatePurchaseOrder createPurchaseOrder;
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

    private static MockHttpServletRequestBuilder create(String token, String body) {
        MockHttpServletRequestBuilder request = post(ORDERS);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    @Test
    void creatingAnOrderIs401WithoutAValidBearer() throws Exception {
        for (String token : new String[]{null, "bad"}) {
            mvc.perform(create(token, "{\"supplierId\":\"" + SUPPLIER + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        verifyNoInteractions(createPurchaseOrder);
    }

    @Test
    void aGrantedUserCreatesAnOrderAsTheBearerPrincipal() throws Exception {
        when(createPurchaseOrder.create(STANDARD_ID, SUPPLIER)).thenReturn(ORDER_ID);

        mvc.perform(create("standard-token", "{\"supplierId\":\"" + SUPPLIER + "\",\"userId\":\"" + ADMIN_ID
                        + "\",\"role\":\"ADMIN\",\"permission\":\"PURCHASE_ORDER_CREATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));

        verify(createPurchaseOrder).create(STANDARD_ID, SUPPLIER);
        verifyNoMoreInteractions(createPurchaseOrder);
    }

    @Test
    void theSupplierIsOptional() throws Exception {
        when(createPurchaseOrder.create(any(), isNull())).thenReturn(ORDER_ID);

        mvc.perform(create("standard-token", null)).andExpect(status().isCreated());
        mvc.perform(create("standard-token", "{}")).andExpect(status().isCreated());

        verify(createPurchaseOrder, times(2)).create(STANDARD_ID, null);
    }

    @Test
    void aMissingGrantIs403EvenForAnAdmin() throws Exception {
        when(createPurchaseOrder.create(any(), any())).thenThrow(new PurchaseOrderPermissionDeniedException());

        for (String token : new String[]{"standard-token", "admin-token"}) {
            String body = mvc.perform(create(token, "{\"supplierId\":\"" + SUPPLIER + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Forbidden"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("PURCHASE_ORDER_CREATE").doesNotContain("permission")
                    .doesNotContain("Exception");
        }

        verify(createPurchaseOrder).create(STANDARD_ID, SUPPLIER);
        verify(createPurchaseOrder).create(ADMIN_ID, SUPPLIER);
    }

    @Test
    void aMalformedSupplierIs400WithoutCallingTheUseCase() throws Exception {
        for (String body : new String[]{"{\"supplierId\":\"not-a-uuid\"}", "{\"supplierId\":1}", "oops"}) {
            String response = mvc.perform(create("standard-token", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("not-a-uuid").doesNotContain("oops");
        }
        verifyNoInteractions(createPurchaseOrder);
    }

    @Test
    void otherOrderRoutesStayClosed() throws Exception {
        mvc.perform(get(ORDERS).header("Authorization", "Bearer admin-token")).andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/" + ORDER_ID).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ORDERS + "/" + ORDER_ID + "/submit").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createPurchaseOrder);
    }
}
