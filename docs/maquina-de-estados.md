# Máquina de estados de la Orden de Compra

Cubre RF-NEG-03, RF-NEG-04, RF-NEG-05 y RD-04. Módulo `com.stockflow.inventory`.

## Entidad

`PurchaseOrder` (`inventory/domain/PurchaseOrder.java`), persistida en la tabla `purchase_orders`
(migración `V2__purchase_order_state_machine.sql`).

| Atributo | Tipo Java | Columna | Obligatorio |
|---|---|---|---|
| `id` | `UUID` | `id uuid PRIMARY KEY` | sí |
| `supplierId` | `UUID` (se expone como `Optional<UUID>`) | `supplier_id uuid NULL` | no |
| `status` | `PurchaseOrderStatus` | `state varchar(16) NOT NULL` | sí |
| `createdAt` | `Instant` (UTC) | `created_at timestamptz NOT NULL` | sí |

Toda orden nueva (`PurchaseOrder.create`) empieza en `DRAFT`. `PurchaseOrder.restore` solo reconstruye una orden ya
persistida. `supplier_id` no tiene clave foránea: todavía no existe la entidad proveedor.

## Estados

Declarados una sola vez en el enum `PurchaseOrderStatus`:

| Estado | Significado | Terminal |
|---|---|---|
| `DRAFT` | Borrador | no |
| `SUBMITTED` | Enviada para revisión | no |
| `APPROVED` | Aprobada, pendiente de recepción | no |
| `RECEIVED` | Recibida | **sí** |
| `CANCELLED` | Cancelada | **sí** |

En la base de datos, la restricción `ck_purchase_orders_state` repite los cinco literales para que ningún otro valor
pueda guardarse aunque no pase por el código.

## Transiciones

`PurchaseOrderTransitionPolicy` es la única fuente de las transiciones permitidas. `PurchaseOrder.transitionTo` le
pide validar la transición y solo cambia el estado si es válida; si no, lanza
`InvalidPurchaseOrderTransitionException` y la orden queda igual.

| Desde | Hacia | Operación (P3-F1) | Quién ejecuta | Condición |
|---|---|---|---|---|
| `DRAFT` | `SUBMITTED` | `SubmitPurchaseOrder` | STANDARD o ADMIN activo | La orden está en `DRAFT` **y tiene `supplierId`** |
| `DRAFT` | `CANCELLED` | `CancelPurchaseOrder` | STANDARD o ADMIN activo | La orden está en `DRAFT` |
| `SUBMITTED` | `APPROVED` | `ApprovePurchaseOrder` | ADMIN activo | La orden está en `SUBMITTED` |
| `SUBMITTED` | `CANCELLED` | `CancelPurchaseOrder` | ADMIN activo | La orden está en `SUBMITTED` |
| `APPROVED` | `RECEIVED` | `ReceivePurchaseOrder` | ADMIN activo | La orden está en `APPROVED` |
| `APPROVED` | `CANCELLED` | `CancelPurchaseOrder` | ADMIN activo | La orden está en `APPROVED` |
| `SUBMITTED` | `RECEIVED` | — | Nadie | **Prohibida**: no se puede saltar la aprobación |
| `RECEIVED` | `DRAFT` | — | Nadie | **Prohibida**: `RECEIVED` es terminal |
| `CANCELLED` | `SUBMITTED` | — | Nadie | **Prohibida**: `CANCELLED` es terminal |

La creación (`CreatePurchaseOrder`) no es una transición: deja la orden en `DRAFT`, con `supplierId` opcional. Antes
de construir o guardar nada, el caso de uso comprueba con datos del servidor (`UserAdministrationRepository` y
Gestión de Permisos):

1. que el usuario existe;
2. que su cuenta está `ACTIVE`;
3. que su rol es `ADMIN` o `STANDARD`;
4. que tiene concedido el permiso persistido `PURCHASE_ORDER_CREATE`.

El rol no sustituye al permiso: un ADMIN sin la concesión es rechazado. Las comprobaciones viven en la capa de
aplicación y se aplican también cuando el caso de uso se invoca directamente, sin HTTP. No dependen solo de
`SecurityConfig`: un usuario deshabilitado, pendiente de activación o inexistente no crea órdenes aunque conserve la
concesión.

**Condición de proveedor.** `PurchaseOrder.submit()` consulta primero la política (un origen distinto de `DRAFT` se
rechaza como transición) y después exige `supplierId`; si falta, lanza `PurchaseOrderSupplierRequiredException` y la
orden sigue en `DRAFT`. Una orden sin proveedor puede crearse y cancelarse, pero no enviarse. No se valida que el
proveedor exista: todavía no hay entidad proveedor.

**Cancelación.** La ruta admite STANDARD o ADMIN; `CancelPurchaseOrder` revalida el rol con el estado ya bloqueado:
un STANDARD solo cancela un borrador, y una orden que salió de `DRAFT` solo la cancela un ADMIN activo.

### Reglas

- **Rechazo por defecto:** cualquier par que no aparezca como permitido se rechaza, no solo los tres prohibidos
  que se nombran en la tabla. De los 25 pares posibles, 6 están permitidos y 19 se rechazan.
- **Terminales:** `RECEIVED` y `CANCELLED` no tienen ninguna transición de salida. Un estado es terminal si la
  política no le permite ningún destino; no hay una segunda lista de terminales.
- **Mismo estado:** pasar al estado actual (por ejemplo `DRAFT -> DRAFT`) se rechaza.
- **Valores nulos:** un estado de origen o destino nulo se rechaza con `NullPointerException`.
- **Estado intacto:** toda transición rechazada (política, proveedor, rol, orden inexistente) deja la orden igual y
  el caso de uso no guarda nada.

```text
DRAFT ──► SUBMITTED ──► APPROVED ──► RECEIVED (terminal)
  │           │            │
  └───────────┴────────────┴──────► CANCELLED (terminal)
```

## Casos de uso, HTTP y persistencia (P3-F1)

| Operación | Ruta | Respuesta | Autorización en `SecurityConfig` | Revalidación en el caso de uso |
|---|---|---|---|---|
| Crear | `POST /api/v1/purchase-orders` | `201 {"id": "..."}` | Bearer | Actor STANDARD/ADMIN `ACTIVE` **y** concesión `PURCHASE_ORDER_CREATE` |
| Enviar | `POST /api/v1/purchase-orders/{id}/submit` | `204` | STANDARD o ADMIN | Actor STANDARD/ADMIN `ACTIVE` |
| Aprobar | `POST /api/v1/purchase-orders/{id}/approve` | `204` | ADMIN | Actor ADMIN `ACTIVE` |
| Recibir | `POST /api/v1/purchase-orders/{id}/receive` | `204` | ADMIN | Actor ADMIN `ACTIVE` |
| Cancelar | `POST /api/v1/purchase-orders/{id}/cancel` | `204` | STANDARD o ADMIN | Actor `ACTIVE`; ADMIN si la orden no está en `DRAFT` |

El actor sale siempre del principal que crea `SessionAuthenticationFilter` (rol y estado leídos de la base en cada
petición); las rutas de transición no leen cuerpo, así que ningún `userId`, rol o estado enviado por el cliente
influye. Errores en ProblemDetail genérico: `401` sin sesión válida, `403` por rol o permiso insuficiente, `404` orden
inexistente, `409` transición prohibida o falta de proveedor (sin revelar el estado), `400` UUID o cuerpo inválido.

Cada transición es `@Transactional`: revalida el actor, carga la orden con `PurchaseOrderRepository.lockById`
(`SELECT ... FOR UPDATE`), aplica la transición en el agregado y guarda; `save` solo actualiza `state`. Dos
transiciones simultáneas sobre la misma orden se serializan: la segunda relee el estado ya cambiado y la política la
rechaza, así que ninguna sobrescribe un estado más reciente. No hay migración nueva: se reutiliza `purchase_orders`
(V2).

Fuera de alcance: proveedores, productos, líneas, movimientos de stock, listados o consultas de órdenes.

## Pruebas

- `PurchaseOrderTransitionPolicyTest`: los cinco estados, las 6 transiciones permitidas, los 19 pares rechazados
  (entre ellos `SUBMITTED -> RECEIVED`), los terminales (`receivedIsTerminalAndCannotReturnToDraft`), el mismo estado
  y los valores nulos.
- `PurchaseOrderTest`: la orden empieza en `DRAFT`, `submit()` exige proveedor
  (`aDraftWithoutSupplierCannotBeSubmittedAndStaysDraft`) y el estado no cambia cuando una transición se rechaza.
- `CreatePurchaseOrderTest` y `PurchaseOrderTransitionUseCasesTest`: permiso de creación (también para un ADMIN sin
  concesión), actor deshabilitado, inexistente o con rol no válido rechazado aunque conserve la concesión, rol y
  estado del actor por operación, orden inexistente, transición inválida y estado intacto sin guardar.
- `PurchaseOrderWebTest` y `PurchaseOrderTransitionWebTest`: sesión válida/ausente, rol por ruta, actor del
  principal, UUID inválido y respuestas `400/401/403/404/409`.
- `PurchaseOrderPersistenceIT`, `PurchaseOrderLifecycleHttpIT` y `PurchaseOrderTransitionConcurrencyIT` (PostgreSQL
  real): migración V2, persistencia de cada transición tras una conexión nueva, rechazos sin cambiar la fila,
  rollback y transiciones concurrentes serializadas por el bloqueo de fila. La prueba de concurrencia coordina sus
  propias transacciones con latches y comprueba la espera solo con los PID de sus conexiones, sin contadores
  globales.
- `CreatePurchaseOrderAuthorizationIT` (PostgreSQL real): invocación directa de `CreatePurchaseOrder`; con la
  concesión persistida, una cuenta `DISABLED` o `PENDING_ACTIVATION` es rechazada y no se guarda ninguna orden.
- `PermissionHttpIT.anOrderIsForbiddenUntilTheApprovalAppliesTheGrant`: integración con Gestión de Permisos.
