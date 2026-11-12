package com.stockflow.document.application;
import com.stockflow.document.domain.Document;
import com.stockflow.identity.application.UserAdministrationRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant; import java.util.Optional; import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatCode; import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
class DocumentActorAuthorizerTest {
 @Test void rejectsInactiveAndForeignStandardButAllowsOwnerAndAdmin(){
  var users=mock(UserAdministrationRepository.class); var a=new DocumentActorAuthorizer(users); UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
  var d=Document.create(UUID.randomUUID(),owner,"a.pdf","application/pdf",1,UUID.randomUUID(),Instant.now());
  when(users.find(owner)).thenReturn(Optional.of(new UserAdministrationRepository.ManagedUser(owner,"x","STANDARD","ACTIVE",true)));
  assertThatCode(()->a.requireOwnerOrAdmin(owner,d)).doesNotThrowAnyException();
  when(users.find(other)).thenReturn(Optional.of(new UserAdministrationRepository.ManagedUser(other,"x","STANDARD","ACTIVE",true)));
  assertThatThrownBy(()->a.requireOwnerOrAdmin(other,d)).isInstanceOf(DocumentAccessDeniedException.class);
  when(users.find(other)).thenReturn(Optional.of(new UserAdministrationRepository.ManagedUser(other,"x","ADMIN","ACTIVE",true)));
  assertThatCode(()->a.requireOwnerOrAdmin(other,d)).doesNotThrowAnyException();
  when(users.find(other)).thenReturn(Optional.of(new UserAdministrationRepository.ManagedUser(other,"x","STANDARD","DISABLED",true)));
  assertThatThrownBy(()->a.requireActive(other)).isInstanceOf(DocumentActorNotAuthorizedException.class);
 }
}
