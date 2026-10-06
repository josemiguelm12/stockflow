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

| Desde | Hacia | Quién ejecuta | Condición |
|---|---|---|---|
| `DRAFT` | `SUBMITTED` | Previsto: usuario autenticado (STANDARD o ADMIN) | La orden está en `DRAFT` |
| `DRAFT` | `CANCELLED` | Previsto: usuario autenticado (STANDARD o ADMIN) | La orden está en `DRAFT` |
| `SUBMITTED` | `APPROVED` | Previsto: ADMIN | La orden está en `SUBMITTED` |
| `SUBMITTED` | `CANCELLED` | Previsto: ADMIN | La orden está en `SUBMITTED` |
| `APPROVED` | `RECEIVED` | Previsto: ADMIN | La orden está en `APPROVED` |
| `APPROVED` | `CANCELLED` | Previsto: ADMIN | La orden está en `APPROVED` |
| `SUBMITTED` | `RECEIVED` | Nadie | **Prohibida**: no se puede saltar la aprobación |
| `RECEIVED` | `DRAFT` | Nadie | **Prohibida**: `RECEIVED` es terminal |
| `CANCELLED` | `SUBMITTED` | Nadie | **Prohibida**: `CANCELLED` es terminal |

"Quién ejecuta" es el actor previsto para cuando exista la operación de negocio. En T05 ningún actor puede ejecutar
transiciones (ver más abajo), así que hoy la autorización por rol no se aplica en ningún sitio.

La única condición que se comprueba hoy es el estado de origen. Las condiciones de negocio (que la orden tenga
líneas, que la recepción esté confirmada, el proveedor, el stock) son trabajo futuro y no se evalúan.

### Reglas

- **Rechazo por defecto:** cualquier par que no aparezca como permitido se rechaza, no solo los tres prohibidos
  que se nombran en la tabla. De los 25 pares posibles, 6 están permitidos y 19 se rechazan.
- **Terminales:** `RECEIVED` y `CANCELLED` no tienen ninguna transición de salida. Un estado es terminal si la
  política no le permite ningún destino; no hay una segunda lista de terminales.
- **Mismo estado:** pasar al estado actual (por ejemplo `DRAFT -> DRAFT`) se rechaza.
- **Valores nulos:** un estado de origen o destino nulo se rechaza con `NullPointerException`.

```text
DRAFT ──► SUBMITTED ──► APPROVED ──► RECEIVED (terminal)
  │           │            │
  └───────────┴────────────┴──────► CANCELLED (terminal)
```

## Qué no incluye T05

T05 solo crea la estructura: la entidad persistida, los estados, la política y las pruebas. **No expone endpoints
REST**, no tiene casos de uso, interfaz ni CRUD de órdenes, y no crea proveedores, productos, líneas ni movimientos
de stock.

## Pruebas

- `PurchaseOrderTransitionPolicyTest`: las 6 transiciones permitidas, los 19 pares rechazados (entre ellos
  `SUBMITTED -> RECEIVED`), los terminales, el mismo estado y los valores nulos.
- `PurchaseOrderTest`: la orden empieza en `DRAFT` y no cambia de estado cuando una transición se rechaza.
- `PurchaseOrderPersistenceIT` (PostgreSQL real): la migración V2, la restricción `CHECK` y el guardado y lectura
  de órdenes con y sin proveedor.
