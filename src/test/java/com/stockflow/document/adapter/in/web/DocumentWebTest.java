package com.stockflow.document.adapter.in.web;
import com.stockflow.document.application.*;
import com.stockflow.document.domain.Document;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.shared.config.SecurityConfig;
import com.stockflow.support.WebTestClock;
import org.junit.jupiter.api.BeforeEach; import org.junit.jupiter.api.Test; import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest; import org.springframework.context.annotation.Import; import org.springframework.boot.test.context.TestConfiguration; import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource; import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc; import org.springframework.mock.web.MockMultipartFile;
import java.time.Instant; import java.util.Optional; import java.util.UUID;
import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*; import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=DocumentController.class) @Import({SecurityConfig.class,WebTestClock.class,DocumentWebTest.DocumentUseCaseMocks.class})
@TestPropertySource(properties={"stockflow.cors.allowed-origins=http://localhost:4200","stockflow.rate-limit.global-per-minute=100000"})
class DocumentWebTest {
 static final UUID USER=UUID.randomUUID(), ID=UUID.randomUUID(); @Autowired MockMvc mvc;
 @MockitoBean UploadDocument upload; @MockitoBean ListVisibleDocuments list; @MockitoBean DownloadDocument download; @MockitoBean DeleteDocument delete; @MockitoBean AuthenticateSession sessions;
 @BeforeEach void auth(){when(sessions.authenticate("token")).thenReturn(Optional.of(new AuthenticatedUser(USER,"u@test","STANDARD",UUID.randomUUID())));}
 @Test void routesRequireBearer() throws Exception { mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized()); verifyNoInteractions(list); }
 @Test void uploadUsesAuthenticatedActor() throws Exception { var d=Document.create(ID,USER,"invoice.pdf","application/pdf",3,UUID.randomUUID(),Instant.now()); when(upload.upload(eq(USER),any())).thenReturn(d);
  mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file","invoice.pdf","application/pdf",new byte[]{1,2,3})).header("Authorization","Bearer token"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(ID.toString())); verify(upload).upload(eq(USER),any()); }
 @Test void emptyFileIsBadRequest() throws Exception { mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file","a.pdf","application/pdf",new byte[0])).header("Authorization","Bearer token")).andExpect(status().isBadRequest()); verifyNoInteractions(upload); }
 @TestConfiguration static class DocumentUseCaseMocks { @Bean ExtractFinancialDocument extractFinancialDocument(){return mock(ExtractFinancialDocument.class);}@Bean GetFinancialExtractionDraft getFinancialExtractionDraft(){return mock(GetFinancialExtractionDraft.class);} }
}
