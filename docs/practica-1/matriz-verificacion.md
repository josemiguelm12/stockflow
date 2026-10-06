# Matriz de verificación — Práctica 1

Relaciona cada requisito de la Práctica 1 con el código que lo implementa, la evidencia que existe y la forma de
reproducirlo. Los pasos manuales están en [guia-verificacion.md](guia-verificacion.md) (citada como **Guía §N**).

## Estados

| Estado | Significado |
|---|---|
| `IMPLEMENTADO` | El código existe y lo cubren pruebas automáticas, que pasaron en la última ejecución completa (ver abajo). |
| `EVIDENCIA PREVIA` | Además de las pruebas automáticas, una verificación manual previa ya confirmó el criterio (correo real). |
| `PENDIENTE DE TAG` | Depende del tag `practica-1`, que se crea después de fusionar la documentación final. |

**Evidencia automática previa:** la última ejecución independiente de `./mvnw.cmd verify -Pintegration-test`
corrió sobre el mismo código funcional que hay en `main` (PR #11). Pasaron 143 pruebas unitarias/web y 103 de
integración, sin fallos, con PostgreSQL 16 en Testcontainers. Esa batería **no se volvió a ejecutar** al preparar
esta documentación.

**Evidencia manual previa (correo real):**

- **T01-07:** el registro llegó a un buzón real a través del worker, y el enlace activó la cuenta (la landing mostró
  "Cuenta activada").
- **T03-13:** `forgot` respondió `202`. El worker envió el correo de recuperación por SMTP real y lo marcó
  `SENT`. El propietario abrió el enlace y envió el formulario. Al final, el token quedó consumido, sin sesiones
  vigentes y con `password_reset_required=false`.

Para la administración de usuarios (T04) no hubo verificación manual: su evidencia son solo las pruebas automáticas.

**Cómo ejecutar una sola clase de prueba:**

- Unitarias: `./mvnw test -Dtest=<Clase>`.
- Integración: `./mvnw verify -Pintegration-test -Dit.test=<Clase>`. Requiere Docker y también ejecuta las pruebas
  unitarias.
- En PowerShell, use `.\mvnw.cmd` y ponga entre comillas los argumentos `-D`, porque PowerShell parte
  `-Dit.test=...` en el punto: `.\mvnw.cmd verify -Pintegration-test "-Dit.test=<Clase>"`.

## Registro y activación

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RF-CA-01 | Un segundo registro con el mismo correo se rechaza, aunque cambien mayúsculas o espacios | `RegisterUser`, `EmailNormalizer`, índice único sobre `users.email_normalized` (V1) | `RegistrationActivationIT.duplicateEmailIsRejectedEvenWithSpacesAndUppercase`, `FoundationIT.emailNormalizedIsUnique`, `AuthWebTest.duplicateEmailIs409` | Guía §4.1 (`409`) | `IMPLEMENTADO` |
| RF-CA-02 | Las contraseñas se guardan con hash y sal; nunca en texto plano | `BcryptPasswordHasher` | `RegistrationActivationIT.samePasswordForTwoUsersProducesDifferentHashes`, `registrationCreatesPendingUserWithSaltedHashAndEncryptedOutbox` | Guía §4.2 (consulta de `password_hash`) | `IMPLEMENTADO` |
| RF-CA-14 | Contraseña de al menos 8 caracteres con letras y números, en el registro y en todos los cambios | `PasswordPolicy` (usada por `RegisterUser`, `ResetPassword`, `ChangePassword`, `BootstrapAdmin`) | `DomainRulesTest.passwordPolicyRequiresLengthLetterAndDigit`, `RegistrationActivationIT.invalidEmailOrPasswordIs400AndCreatesNothing`, `PasswordRecoveryIT.anInvalidNewPasswordNeitherConsumesTheTokenNorChangesAnything`, `PasswordRecoveryIT.aWeakNewPasswordIsRejectedWithoutChangingAnything`, `AdminBootstrapIT.invalidOrMissingInputFailsSafelyWithoutMutatingAnything` | Guía §4.1 y §6 (`400`) | `IMPLEMENTADO` |
| RF-CA-15 | La cuenta nace inactiva; el correo de activación con token de un uso y vencimiento sale por la cola | `RegisterUser`, `ActivationIssuer`, `OutboxActivationEmailAdapter`, `ActivationEmailOutbox` | `RegistrationActivationIT.registrationCreatesPendingUserWithSaltedHashAndEncryptedOutbox`, `SessionAuthenticationIT.inactiveAccountsWithTheCorrectPasswordAreRejectedAsNotActiveWithoutASession`; correo real T01-07 | Guía §4.2 (login `403` antes de activar) y §8 | `EVIDENCIA PREVIA` |
| RF-CA-16 | El enlace activa la cuenta; un segundo uso o un token vencido se rechaza sin cambiar nada | `ActivateAccount`, `ActivationLandingController` (`GET /activate`), `POST /api/v1/auth/activate` | `RegistrationActivationIT.validActivationActivatesOnceAndReuseFailsWithoutMutating`, `expiredTokenIsRejectedAndTheAccountStaysPending`, `concurrentActivationsWithTheSameTokenSucceedOnlyOnce`; correo real T01-07 | Guía §4.3 y §4.4 | `EVIDENCIA PREVIA` |
| RF-CA-17 | El reenvío responde igual exista o no la cuenta e invalida el token anterior | `ResendActivation`, `POST /api/v1/auth/resend-activation` | `RegistrationActivationIT.resendRespondsTheSameForExistingAndUnknownAccountsAndInvalidatesThePreviousToken`, `ActivationLifecycleConcurrencyIT.twoResendsWaitingOnTheUserLockLeaveExactlyOneUsableToken` | Guía §4.5 | `IMPLEMENTADO` |

## Sesión

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RF-CA-03 | El login entrega un token; si falla, no revela si el error fue el correo o la contraseña | `Login`, `JwtAccessTokenService`, `POST /api/v1/auth/login` | `SessionAuthenticationIT.loginIssuesAFifteenMinuteJwtWithMinimalClaimsAndAPersistedSession`, `unknownEmailAndWrongPasswordGetTheExactSame401`, `JwtAccessTokenServiceTest` | Guía §5.1 (`200` / `401` idéntico) | `IMPLEMENTADO` |
| RF-CA-07 | `/me` devuelve el usuario y su rol; sin sesión válida se rechaza | `AuthenticateSession`, `SessionAuthenticationFilter`, `GET /api/v1/auth/me` | `SessionAuthenticationIT.meReturnsOnlyIdEmailAndTheCurrentRoleReadFromTheServer`, `invalidMissingForgedExpiredRevokedAndSessionlessTokensAreRejected` | Guía §5.2 | `IMPLEMENTADO` |
| RF-CA-18 | Tras el logout, el token deja de servir | `Logout`, `JdbcSessionRepository.revoke`, `POST /api/v1/auth/logout` | `SessionAuthenticationIT.logoutRevokesTheSessionImmediatelyAndLeavesOtherSessionsAlone` | Guía §5.3 (`204`, luego `401`) | `IMPLEMENTADO` |
| RF-CA-19 | El quinto fallo bloquea la cuenta 15 min; el sexto intento se rechaza aunque sea correcto; un acierto reinicia el contador | `Login`, `JdbcUserRepository` (bloqueo de fila) | `SessionAuthenticationIT.fiveFailuresLockTheAccountForFifteenMinutesAndASuccessAfterwardClearsIt`, `aSuccessBeforeTheThresholdResetsTheCounter`, `concurrentFailuresAreNeverLostAndTheThresholdLocksTheAccount`, `RateLimitIT` | Guía §5.4 | `IMPLEMENTADO` |

## Roles y usuarios

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RF-CA-04 | Solo existen los roles `ADMIN` y `STANDARD`, y cada usuario tiene exactamente uno | `CHECK ck_users_role` (V1), `ChangeUserRole` | `UserAdministrationIT.anAdminChangesAnotherUsersRoleAndTheChangeAppliesToTheirNextRequest` (rol inválido `400`), `RegistrationActivationIT.registrationCannotChooseRoleOrStatus` | Guía §7.4 | `IMPLEMENTADO` |
| RF-CA-05 | El rol que exige cada operación se declara en un solo punto del código | `SecurityConfig` (`hasRole("ADMIN")` por ruta), `AdminGuard` | `AdminWebTest.everyAdminEndpointIs403ForAStandardUserAndNeverReachesTheUseCases`, `AuthWebTest.onlyTheAuthorizedRoutesAndMethodsAreOpen` | Leer `SecurityConfig`; Guía §7.2 | `IMPLEMENTADO` |
| RF-CA-06 | Un usuario STANDARD no puede ejecutar acciones de ADMIN, tampoco con requests manuales | `SecurityConfig`, `AdminGuard` | `UserAdministrationIT.withoutABearerEveryAdminEndpointIs401AndAStandardBearerIs403WithoutChangingAnything` | Guía §7.2 (`401` / `403`) | `IMPLEMENTADO` |
| RF-CA-08 | Solo un ADMIN cambia roles; nadie cambia su propio rol; siempre queda al menos un ADMIN activo | `ChangeUserRole`, `PATCH /api/v1/admin/users/{id}/role` | `UserAdministrationIT.anAdminChangesAnotherUsersRoleAndTheChangeAppliesToTheirNextRequest`, `anAdminCannotChangeTheirOwnRole`, `twoAdminsDemotingEachOtherAtTheSameTimeNeverLeaveZeroActiveAdmins` | Guía §7.4 | `IMPLEMENTADO` |
| RF-CA-20 | Un ADMIN desactiva y reactiva usuarios; el desactivado no puede entrar y sus sesiones dejan de servir; un ADMIN no se desactiva a sí mismo | `ChangeUserStatus`, `PATCH /api/v1/admin/users/{id}/status` | `UserAdministrationIT.disablingAUserRevokesTheirSessionsAndBlocksLogin`, `anAdminCannotDisableThemselvesAndTwoAdminsDisablingEachOtherKeepOneActive`, `reactivationRestoresAccessWithoutRevivingSessionsOrTouchingTheLockout` | Guía §7.5 | `IMPLEMENTADO` |
| RF-CA-21 | Un ADMIN lista usuarios con rol y estado, sin hashes ni tokens; STANDARD recibe un rechazo | `ListUsers`, `JdbcUserAdministrationRepository`, `AdminUserPageResponse`, `GET /api/v1/admin/users` | `UserAdministrationIT.theListIsPagedInAStableOrderWithOnlyTheAllowedFields`, `AdminWebTest.theListHasExactlyTheAllowedFields` | Guía §7.3 | `IMPLEMENTADO` |

## Contraseñas

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RF-CA-09 | La solicitud de recuperación responde igual exista o no el correo | `RequestPasswordReset`, `POST /api/v1/auth/password/forgot` | `PasswordRecoveryIT.forgotRespondsIdenticallyForEveryAccountStateAndOnlyAnActiveAccountGetsATokenAndAnEmail` | Guía §6.1 | `IMPLEMENTADO` |
| RF-CA-10 | El código de recuperación es de un solo uso, vence y se envía por la cola; uno usado o vencido se rechaza sin cambiar la contraseña | `PasswordResetIssuer`, `ResetPassword`, `OutboxPasswordResetEmailAdapter`, `PasswordResetEmailOutbox` | `PasswordRecoveryIT.theTokenIsRandomHashedOnlyAndValidForThirtyMinutes`, `theTokenWorksUntilTheExactExpirationInstant`, `everyKindOfUnusableTokenGetsTheSame400AndChangesNothing`, `PasswordLifecycleConcurrencyIT.concurrentRedemptionsOfTheSameTokenSucceedAtMostOnce`; correo real T03-13 | Guía §6.2 y §6.3 | `EVIDENCIA PREVIA` |
| RF-CA-11 | Con un código válido se define una contraseña nueva; la anterior deja de servir | `ResetPassword`, `POST /api/v1/auth/password/reset` | `PasswordRecoveryIT.aValidTokenChangesTheHashAndOnlyTheNewPasswordLogsIn`; reset manual T03-13 | Guía §6.2 | `EVIDENCIA PREVIA` |
| RF-CA-12 | Un cambio o reset de contraseña invalida todas las sesiones previas | `ResetPassword`, `ChangePassword`, `SessionRepository.revokeAllForUser` | `PasswordRecoveryIT.aResetRevokesEverySessionOfTheUserAndCreatesNoneAndLeavesOtherUsersAlone`, `aSuccessfulChangeUpdatesTheHashInvalidatesResetTokensAndRevokesEverySessionIncludingTheCurrentOne`, `PasswordLifecycleConcurrencyIT.aLoginRacingAResetNeverLeavesALiveSessionWithTheOldPassword` | Guía §6.2 y §6.4 (Bearer anterior `401`) | `IMPLEMENTADO` |
| RF-CA-13 | Un ADMIN fuerza el reset: la contraseña anterior deja de servir y se encola un código nuevo | `ForcePasswordReset`, `POST /api/v1/admin/users/{id}/force-password-reset` | `UserAdministrationIT.forcePasswordResetFlagsTheAccountRevokesSessionsAndQueuesAnEncryptedEmail`, `aSecondForceResetInvalidatesTheFirstAndOnlyTheLastTokenCompletesTheReset`, `anOfflineSmtpKeepsThe202AndTheWorkerLaterDeliversTheResetEmailOnce` (sin verificación manual de T04) | Guía §7.6 | `IMPLEMENTADO` |
| RF-CA-22 | Un usuario autenticado cambia su contraseña indicando la actual; si la actual es errónea se rechaza; aplica la política y la revocación | `ChangePassword`, `POST /api/v1/auth/password/change` | `PasswordRecoveryIT.changeRequiresAnAuthenticatedUser`, `aWrongCurrentPasswordIsAGeneric400AndMutatesNothing`, `aWeakNewPasswordIsRejectedWithoutChangingAnything`, `changeCannotTargetAnotherUser` | Guía §6.4 | `IMPLEMENTADO` |

## Notificaciones (cola mínima)

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RF-NOT-08 | Activación, recuperación y reset forzado guardan el correo en una cola persistente (`outbound_emails`, cifrado) sin llamar a SMTP | `ActivationEmailOutbox`, `PasswordResetEmailOutbox`, `JdbcOutboundEmailRepository`, `OutboxPayloadCipher` | `RegistrationActivationIT.registrationCreatesPendingUserWithSaltedHashAndEncryptedOutbox`, `PasswordRecoveryIT.forgotLeavesAnEncryptedPendingEmailAndNeverCallsSmtp`, `UserAdministrationIT.forcePasswordResetFlagsTheAccountRevokesSessionsAndQueuesAnEncryptedEmail`, `OutboxPayloadCipherTest` | Guía §8.1 | `IMPLEMENTADO` |
| RF-NOT-09 | Con SMTP caído la operación tiene éxito y el correo queda `PENDING`; un proceso aparte envía los pendientes y los marca `SENT` | `OutboxWorkerConfig` (`STOCKFLOW_WORKER_ENABLED=true`), `OutboxDispatcher`, `SmtpEmailSender` | `RegistrationActivationIT.smtpDownDoesNotRevertRegistrationOrResendAndLeavesEmailsPending`, `PasswordRecoveryIT.anOfflineSmtpDoesNotChangeTheAcceptedResponseAndLeavesTheEmailPending`, `UserAdministrationIT.anOfflineSmtpKeepsThe202AndTheWorkerLaterDeliversTheResetEmailOnce`, `WorkerModeIT` | Guía §8.1 y §8.2 | `IMPLEMENTADO` |
| RF-NOT-12 | Ejecutar el worker dos veces no reenvía los correos ya `SENT` | `OutboxDispatcher` (reclamo transaccional y estado `SENT`) | `RegistrationActivationIT.workerSendsOnceMarksSentPurgesPayloadAndASecondRunDoesNotResend`, `twoConcurrentWorkersNeverSendTheSameEmailTwice`, `PasswordRecoveryIT.theWorkerDeliversTheResetTemplatePurgesThePayloadAndNeverResendsAndActivationStillWorks` | Guía §8.3 (`sent=0`) | `IMPLEMENTADO` |
| RF-NOT-13 | Los secretos SMTP vienen de variables de entorno; llega un correo real y su enlace funciona | `SmtpProperties`, `application.properties` (`${STOCKFLOW_SMTP_*}`), `.env.example` | `SmtpEmailSenderTest.deliversToARealSmtpServer` (servidor SMTP de prueba en memoria); correo real T01-07 y T03-13 | Guía §8.2 | `EVIDENCIA PREVIA` |

## Negocio: máquina de estados

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RF-NEG-03 | Entidad central persistida con estado, y entre 3 y 5 estados definidos en un solo lugar | `PurchaseOrder`, `PurchaseOrderStatus` (5 estados), `JdbcPurchaseOrderRepository`, tabla `purchase_orders` (V2) | `PurchaseOrderTransitionPolicyTest.thereAreExactlyTheFiveDeclaredStates`, `PurchaseOrderTest.aNewOrderStartsInDraft`, `PurchaseOrderPersistenceIT` | Guía §9; [maquina-de-estados.md](../maquina-de-estados.md) | `IMPLEMENTADO` |
| RF-NEG-04 | Transiciones centralizadas, con al menos una prohibida explícitamente | `PurchaseOrderTransitionPolicy` | `PurchaseOrderTransitionPolicyTest.submittedCannotSkipApprovalToReceived`, `namedForbiddenTransitionsAreRejected`, `everyOtherPairIsRejectedByDefault` | Guía §9 | `IMPLEMENTADO` |
| RF-NEG-05 | Al menos un estado terminal | `PurchaseOrderTransitionPolicy.isTerminal` (`RECEIVED`, `CANCELLED`) | `PurchaseOrderTransitionPolicyTest.onlyReceivedAndCancelledAreTerminal`, `terminalStatesHaveNoExits`, `PurchaseOrderTest.terminalOrdersCannotLeaveTheirState` | Guía §9 | `IMPLEMENTADO` |
| Docs NEG | `docs/maquina-de-estados.md` con tabla Desde / Hacia / Quién ejecuta / Condición | [maquina-de-estados.md](../maquina-de-estados.md) | Documento versionado (PR #11) | Leer el documento | `IMPLEMENTADO` |

## Requisitos de diseño

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| RD-04 | Las transiciones de estado están centralizadas | `PurchaseOrderTransitionPolicy`; `PurchaseOrder.transitionTo` delega en ella | `PurchaseOrderTest.aRejectedTransitionLeavesTheStateUnchanged`, `PurchaseOrderTransitionPolicyTest` | Guía §9 | `IMPLEMENTADO` |
| RD-05 | Hash con sal | `BcryptPasswordHasher` | Igual que RF-CA-02 | Guía §4.2 | `IMPLEMENTADO` |
| RD-06 | La autorización se aplica en el backend | `SecurityConfig` (denegación por defecto), `SessionAuthenticationFilter`, `AdminGuard` | `SecurityConfigTest.unlistedRouteRespondsUnauthorizedWithExplicitEntryPoint`, `AuthWebTest.anAuthenticatedUserStillCannotReachUnlistedRoutes`, `UserAdministrationIT.withoutABearerEveryAdminEndpointIs401AndAStandardBearerIs403WithoutChangingAnything` | Guía §7.2 | `IMPLEMENTADO` |
| RD-07 | La entrada inválida se rechaza de forma controlada | DTOs con Jakarta Validation, `EmailNormalizer`, `ClosedBody`, `ApiExceptionHandler` | `AuthWebTest.invalidBodiesAreRejectedWithAControlledProblem`, `domainValidationErrorsAre400WithoutEchoingTheValue`, `AdminWebTest.requestBodiesAreClosedAndUnknownPropertiesAreRejected`, `AdminWebTest.invalidPagingIsAControlled400` | Guía §4.1 y §7.3 (`400`) | `IMPLEMENTADO` |
| RD-08 | Las respuestas de error no exponen trazas ni consultas | `ApiExceptionHandler` (`500` genérico, `ProblemDetail`) | `AuthWebTest.unexpectedErrorsAre500WithoutDetails`, `SensitiveDataDebugLoggingIT`, `SessionAuthenticationIT.theWholeFlowWorksFromRegistrationAndNothingSensitiveReachesTheLogs`, `PasswordRecoveryIT.theWholeFlowLeavesNoTokenPasswordOrBearerInTheLogs` | Guía §4.1 (cuerpo de los `400`) | `IMPLEMENTADO` |
| RD-09 | Los datos persisten tras un reinicio | PostgreSQL y migraciones Flyway V1/V2 | `FoundationIT.rowSurvivesANewConnection`, `PurchaseOrderPersistenceIT.anOrderWithSupplierKeepsItsStateAndDateAfterTransitions` | Guía §3.2 (reiniciar la API y volver a iniciar sesión) | `IMPLEMENTADO` |
| RD-10 | Los secretos vienen de variables de entorno | `application.properties` (`${...}` sin valores por defecto para los secretos), `.env.example` (solo nombres), `.gitignore` (`.env*`) | `JwtAccessTokenServiceTest.anInvalidSecretFailsFastWithoutLeakingIt`, `OutboxPayloadCipherTest.invalidConfigurationFailsFastWithoutLeakingTheKey`, `PasswordRecoveryPropertiesTest.thePublicUrlIsRequiredAndMustBeAbsoluteHttp` | Guía §1.3 y §10 | `IMPLEMENTADO` |

## Git, README y entrega

| ID | Qué se verifica | Implementación | Evidencia automática o previa | Cómo reproducir | Estado |
|---|---|---|---|---|---|
| GIT-01 | Una rama por funcionalidad, fusionada por PR (mínimo: registro+activación, sesión, recuperación y administración) | PR #7 registro y activación, #8 sesión, #9 recuperación, #10 administración; además #6 foundation y #11 máquina de estados | Historial de merges en `main` | `git log --merges --oneline main` | `IMPLEMENTADO` |
| GIT-02 | Cada PR tiene las secciones «Qué cambia», «Por qué», «Cómo probarlo» y «Qué NO incluye» | `.github/PULL_REQUEST_TEMPLATE.md` | Plantilla versionada; las descripciones de los PR están en GitHub | Abrir los PR #6–#11 en GitHub | `IMPLEMENTADO` |
| GIT-03 | Commits atómicos, con verbos imperativos y los IDs de los RF | Commits de los PR | PR #6–#8: varios commits por PR con los IDs de RF/RD en el asunto. PR #9–#11: un commit por PR (`feat(...)`), sin IDs de RF en el mensaje, así que el requisito solo se cumple parcialmente en esos tres | `git log --no-merges --format="%h %s" main` | `IMPLEMENTADO` |
| GIT-04 | El README incluye prerrequisitos, cómo ejecutar, variables de entorno (solo nombres y propósito) y cómo probar cada criterio | `README.md`, [guia-verificacion.md](guia-verificacion.md), esta matriz | Documentación de esta entrega | Leer el README desde un checkout limpio | `IMPLEMENTADO` |
| GIT-05 | Sin credenciales ni archivos generados en el historial | `.gitignore` (`target/`, `.env*`, `*.log`), `.env.example` vacío | Revisión de `git ls-files` al preparar esta documentación | Guía §10 | `IMPLEMENTADO` |
| GIT-06 | Tag `practica-1` publicado y entregado en Moodle (URL del repositorio y nombre del tag) | README §13 | El tag no existe todavía | Guía §11 | `PENDIENTE DE TAG` |
