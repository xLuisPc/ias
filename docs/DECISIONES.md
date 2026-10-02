# Registro de decisiones técnicas

Cada decisión sigue el formato: **contexto → alternativas → decisión → trade-offs/riesgos → validación**.

Índice:

1. [Arquitectura por capas (hexagonal ligera)](#1-arquitectura-por-capas-hexagonal-ligera)
2. [Persistencia: R2DBC + H2 en modo archivo, SQL explícito](#2-persistencia-r2dbc--h2-en-modo-archivo-sql-explícito)
3. [Concurrencia del cupo (RF04): reserva atómica con UPDATE condicional](#3-concurrencia-del-cupo-rf04-reserva-atómica-con-update-condicional)
4. [Cupo como acumulado en el cliente y no como SUM](#4-cupo-como-acumulado-en-el-cliente-y-no-como-sum)
5. [Referencias repetidas (RF05): idempotencia con UNIQUE + comparación de contenido](#5-referencias-repetidas-rf05-idempotencia-con-unique--comparación-de-contenido)
6. [Reglas de negocio → rechazo persistido; formato inválido → 400](#6-reglas-de-negocio--rechazo-persistido-formato-inválido--400)
7. [Transacción reactiva explícita](#7-transacción-reactiva-explícita)
8. [Diseño de la API y errores](#8-diseño-de-la-api-y-errores)
9. [Fecha de procesamiento y montos](#9-fecha-de-procesamiento-y-montos)
10. [RabbitMQ opcional: publicación best-effort tras el commit](#10-rabbitmq-opcional-publicación-best-effort-tras-el-commit)
11. [Frontend Angular](#11-frontend-angular)
12. [Estrategia de pruebas](#12-estrategia-de-pruebas)

---

## 1. Arquitectura por capas (hexagonal ligera)

**Contexto.** El enunciado pide una arquitectura válida y escalable, pero con un esfuerzo acotado de unas 2 horas.

**Alternativas.**
- *Capas clásicas* (controller → service → repository con entidades JPA/R2DBC). Es simple, pero las reglas quedan acopladas al framework.
- *Hexagonal completa* (módulos separados, mappers en cada capa, casos de uso por clase). Es demasiado para el alcance.
- *Hexagonal ligera.* `domain` (Java puro), `application` (caso de uso + puertos) e `infrastructure` (adaptadores).

**Decisión.** Hexagonal ligera, en un solo módulo Maven y organizada por paquetes.
- `domain`: `CreditPolicy` (reglas), `CreditApplication`, `Customer` y los enums. No conoce Spring ni la base de datos.
- `application`: `CreditApplicationService` orquesta el flujo y depende de interfaces (`CreditApplicationRepository`, `CustomerRepository`, `CreditEventPublisher`).
- `infrastructure`: implementaciones R2DBC, REST y RabbitMQ.

**Trade-offs.** El servicio de aplicación usa dos piezas de Spring, `@Service` y `TransactionalOperator`, por pragmatismo. Separarlas implicaría más configuración sin beneficio real en este alcance.

**Validación.** `CreditPolicyTest` prueba las reglas sin Spring. `CreditApplicationServiceTest` prueba el flujo con puertos simulados. Cambiar H2 por PostgreSQL o RabbitMQ por otro broker solo afecta a `infrastructure`.

---

## 2. Persistencia: R2DBC + H2 en modo archivo, SQL explícito

**Contexto.** Las solicitudes deben persistirse y el backend es WebFlux (reactivo). La tecnología queda a criterio del candidato.

**Alternativas.**
- *JPA/JDBC.* Es bloqueante: rompe el modelo reactivo o exige delegar a otro pool de hilos.
- *MongoDB reactivo.* Hace más complejas las garantías transaccionales del cupo.
- *R2DBC + PostgreSQL.* Es la opción ideal para producción, pero obliga al evaluador a levantar Docker.
- *R2DBC + H2 en memoria.* No persiste entre reinicios.
- *R2DBC + H2 en archivo.* Persiste sin instalar nada.

**Decisión.** R2DBC + H2 en archivo (`backend/data/`). El esquema se crea con `schema.sql` y los clientes semilla con `data.sql`, que solo inserta si el registro no existe para no reiniciar el cupo en cada arranque. El acceso se hace con `DatabaseClient` y SQL explícito, sin entidades Spring Data, por dos motivos:
- la operación clave (reserva de cupo) es una sentencia SQL específica;
- así el dominio queda libre de anotaciones de persistencia.

**Trade-offs y riesgos.**
- H2 en archivo solo admite un proceso: no permite varias instancias. El SQL es estándar y portable a PostgreSQL cambiando solo el driver y la URL.
- `r2dbc-h2` ejecuta sobre el motor embebido de H2, que es síncrono. Con PostgreSQL (`r2dbc-postgresql`) sí sería no bloqueante de verdad.
- No hay herramienta de migraciones (Flyway/Liquibase); el esquema usa `CREATE ... IF NOT EXISTS`.

**Validación.** Las pruebas de integración corren sobre H2 en memoria con el mismo esquema. Probé manualmente que los datos sobreviven a reiniciar la aplicación.

---

## 3. Concurrencia del cupo (RF04): reserva atómica con UPDATE condicional

**Contexto.** Dos solicitudes casi simultáneas del mismo cliente pueden leer el mismo cupo disponible y aprobarse ambas, superando el máximo. Es el clásico problema de *leer → validar → escribir*.

**Alternativas.**
- *Lock en memoria por cliente* (mapa de mutex). No funciona con varias instancias y añade complejidad.
- *Bloqueo pesimista* (`SELECT ... FOR UPDATE`) y luego validar. Funciona, pero necesita dos sentencias y mantiene el lock más tiempo.
- *Bloqueo optimista* (`@Version` + reintentos). Bajo contención genera reintentos y más código.
- *UPDATE condicional atómico.* La validación y la escritura ocurren en una sola sentencia.

**Decisión.**

```sql
UPDATE customer
   SET approved_amount = approved_amount + :amount
 WHERE id = :id
   AND status = 'ELIGIBLE'
   AND approved_amount + :amount <= credit_limit
```

Si afecta 1 fila, el cupo quedó reservado. Si afecta 0, no había cupo y la solicitud se rechaza con `CREDIT_LIMIT_EXCEEDED`. La base de datos bloquea la fila del cliente: las transacciones concurrentes se serializan y la segunda reevalúa la condición con el valor ya actualizado. Como la garantía está en la base de datos, funciona aunque haya varias instancias del servicio.

Hay además dos capas complementarias:
- `CreditPolicy` valida el cupo con una "foto" del cliente para rechazar temprano sin tocar la base de datos. **No** es la garantía.
- Una restricción `CHECK (approved_amount <= credit_limit)` actúa como red de seguridad a nivel de esquema.

**Trade-offs.**
- Todas las solicitudes de un mismo cliente se serializan sobre su fila (*hot row*). Es el comportamiento deseado; solo limitaría el rendimiento si un mismo cliente recibiera miles de solicitudes por segundo.
- El `LOCK_TIMEOUT` es de 10 s. Con contención extrema una solicitud podría fallar con 500 en lugar de rechazarse; no se ve en escenarios normales.
- Si el cliente se bloqueara justo entre la lectura y la reserva, la razón quedaría como `CREDIT_LIMIT_EXCEEDED` y no como `CUSTOMER_NOT_ELIGIBLE`. Es un caso borde, y los clientes no cambian de estado en este ejercicio.

**Validación.**
- `ConcurrencyIntegrationTest` envía 10 solicitudes simultáneas de 3.000.000 contra un cupo de 10.000.000 y comprueba que se aprueban exactamente 3 y que el aprobado queda en 9.000.000.
- Primero el test no era confiable. Con H2 en memoria cada transacción dura microsegundos y las peticiones casi nunca se solapaban, así que **la prueba pasaba incluso con una implementación ingenua**. Lo comprobé quitando la condición del `UPDATE` y el `CHECK`: el test seguía en verde.
- Lo hice determinista con un decorador del repositorio de clientes que retrasa la lectura 300 ms. Todas las peticiones leen el mismo cupo viejo antes de que alguna confirme. Con la implementación ingenua el test falla (10 aprobadas en vez de 3); con la real pasa de forma estable.

---

## 4. Cupo como acumulado en el cliente y no como SUM

**Contexto.** Hay que saber cuánto cupo tiene aprobado cada cliente.

**Alternativas.**
- Calcular `SUM(amount)` de las solicitudes aprobadas en cada evaluación. Es una única fuente de verdad, pero exige bloquear un rango (o la tabla) para que sea seguro ante concurrencia.
- Mantener un acumulado `approved_amount` en `customer`.

**Decisión.** Acumulado en `customer`, actualizado en la **misma transacción** que inserta la solicitud. Permite la reserva atómica de la decisión 3 bloqueando una sola fila.

**Trade-offs.** Es un dato desnormalizado: si alguien modificara las tablas por fuera de la aplicación, podría descuadrarse. Se mitiga con la transacción única y el `CHECK`. Se puede auditar comparando con `SUM` de las aprobadas.

**Validación.** Las pruebas de integración verifican `approvedAmount` después de aprobaciones, rechazos, solicitudes repetidas y escenarios concurrentes.

---

## 5. Referencias repetidas (RF05): idempotencia con UNIQUE + comparación de contenido

**Contexto.** Una misma `applicationReference` puede llegar varias veces, incluso al mismo tiempo. No debe crearse una segunda solicitud ni consumirse cupo otra vez. Si llega con datos distintos, hay que manejarlo de forma controlada.

**Alternativas.**
- Solo consultar antes de insertar. Falla con dos peticiones simultáneas: ambas ven que no existe.
- Lock distribuido o en memoria por referencia. Es complejo y no aporta frente a la base de datos.
- Guardar un hash del payload. Es útil con muchos campos; aquí son tres y la comparación directa es más legible.
- *Consulta previa + restricción `UNIQUE` + comparación de contenido.*

**Decisión.**
1. Si la referencia existe y los datos coinciden (cliente, monto con `compareTo`, plazo), se responde **200** con el resultado original y el header `Idempotent-Replayed: true`.
2. Si existe con datos diferentes, se responde **409 Conflict** con la solicitud original en `originalApplication`. La original nunca se modifica.
3. Si no existe, se procesa. Si otra petición con la misma referencia gana la carrera, la restricción `UNIQUE` hace fallar la inserción y **la transacción se revierte, incluida la reserva de cupo**. Luego se aplica el paso 1 sobre la solicitud ganadora.

**Trade-offs.**
- La referencia es una clave de idempotencia: reenviar una referencia **rechazada** devuelve el mismo rechazo aunque el cliente ya tenga cupo. Para reintentar hay que usar una referencia nueva. Es el comportamiento esperado de la idempotencia.
- La referencia distingue mayúsculas de minúsculas y se le quitan los espacios de los extremos.

**Validación.**
- `concurrentRequestsWithSameReferenceCreateASingleApplication`: 10 peticiones simultáneas con la misma referencia dan exactamente un 201 y nueve 200, una sola fila y el cupo consumido una sola vez. Gracias al retraso del decorador, todas pasan la consulta inicial y se ejercita de verdad el camino del `UNIQUE` y el rollback.
- Las pruebas de API cubren los casos 200 y 409, y la prueba del caso de uso cubre la carrera con dobles de prueba.

---

## 6. Reglas de negocio → rechazo persistido; formato inválido → 400

**Contexto.** ¿Una solicitud con monto 0 o plazo de 72 meses es un error del cliente HTTP o una solicitud rechazada?

**Decisión.** Se separan dos niveles:
- **Forma** (Bean Validation en el DTO): campos obligatorios, longitudes y máximo 2 decimales. Si falla, la respuesta es **400** con el detalle por campo y no se persiste nada, porque sin referencia válida no hay nada que rastrear.
- **Negocio** (`CreditPolicy`): monto ≤ 0, plazo fuera de 6-60, cliente inexistente, bloqueado o sin cupo. El enunciado las presenta como condiciones de *aprobación*, así que generan una solicitud **REJECTED persistida** con su razón (trazabilidad, RF03) y responden 201.

Las reglas se evalúan en este orden: monto → plazo → cliente existe → cliente habilitado → cupo. Se devuelve la primera que falle.

**Trade-offs.** Un cliente cuyo campo no cumple la regla recibe 201 con estado `REJECTED`, no 4xx. Esto queda documentado en el contrato. La UI no duplica estas reglas: solo exige campos obligatorios y muestra el plazo válido como ayuda.

**Validación.** `CreditPolicyTest`, con casos parametrizados y valores límite: 0, -1, plazos 5/6/60/61, cupo exacto y cupo + 0,01. También `returnsBadRequestWithFieldErrorsForMalformedPayload`.

---

## 7. Transacción reactiva explícita

**Contexto.** La reserva de cupo y la inserción de la solicitud deben ser atómicas.

**Alternativas.** `@Transactional` en un método reactivo, o `TransactionalOperator`.

**Decisión.** `TransactionalOperator.transactional(...)` solo alrededor de *evaluar + reservar + insertar*. Así se ve exactamente dónde empieza y termina la transacción. Además, el manejo del `UNIQUE` (`onErrorResume`) y la publicación del evento quedan **fuera** de ella:
- la recuperación ocurre después del rollback;
- el evento solo se publica si hubo commit.

**Validación.** El escenario de referencia duplicada concurrente demuestra que el rollback revierte la reserva de cupo: el aprobado queda en 2.000.000 y no en 2.000.000 × N.

---

## 8. Diseño de la API y errores

**Decisión.**
- **201** para cualquier solicitud nueva procesada, aprobada o rechazada: la solicitud se registró. Incluye `Location` hacia la consulta.
- **200** + `Idempotent-Replayed: true` para la referencia repetida. El cliente distingue sin ambigüedad "procesada ahora" de "ya existía".
- **409** con la solicitud original en el cuerpo.
- **ProblemDetail (RFC 9457)** para errores, extendiendo `ResponseEntityExceptionHandler`. Así se hereda el manejo estándar (JSON mal formado, etc.) y se agrega el detalle por campo en las validaciones.
- Controladores anotados en lugar de *functional endpoints*: el código es más familiar para el equipo y no hay diferencia de rendimiento relevante.
- `GET /api/customers` (solo lectura) se agregó para que el evaluador vea el cupo disponible mientras prueba. No es un módulo de administración.

---

## 9. Fecha de procesamiento y montos

- **Fecha.** La asigna el sistema con un `Clock` inyectado en UTC (`OffsetDateTime`), lo que permite fijarla en pruebas. Se trunca a microsegundos porque esa es la precisión de `TIMESTAMP` en la base de datos. Sin ese truncamiento, la respuesta del POST podía diferir de lo persistido en los nanosegundos; lo detecté por una prueba intermitente que comparaba fechas como texto.
- **Montos.** `BigDecimal` en Java y `DECIMAL(19,2)` en la base de datos, nunca `double`, para evitar errores de redondeo en dinero. El monto se normaliza a escala 2 al entrar (`6000000` y `6000000.00` son iguales) y se compara con `compareTo`.

---

## 10. RabbitMQ opcional: publicación best-effort tras el commit

**Contexto.** Otros sistemas deben poder reaccionar a una aprobación. Es un punto opcional, y la aplicación debe seguir funcionando sin RabbitMQ.

**Decisión.**
- Puerto `CreditEventPublisher` con dos implementaciones, elegidas por `app.rabbitmq.enabled` (falso por defecto):
  - `NoOpCreditEventPublisher`, la de por defecto;
  - `RabbitCreditEventPublisher`.
- Exchange **topic** durable `credit.events` y routing key `credit.application.approved`. Un exchange topic permite que otros sistemas creen sus propias colas sin modificar este servicio.
- Mensaje JSON `CreditApplicationApprovedEvent` con `messageId = applicationReference`, para que los consumidores puedan descartar duplicados.
- Se publica **después del commit** y solo para solicitudes nuevas aprobadas; nunca en un replay.
- `RabbitTemplate` es bloqueante, así que se ejecuta en `Schedulers.boundedElastic()` con timeout de 3 s. Si falla, se registra el error y la respuesta HTTP no se afecta, porque la decisión ya fue persistida.
- `ApprovedApplicationListener` simula un sistema consumidor que registra el evento.

**Trade-offs y riesgos.** Es *best-effort*: si RabbitMQ cae justo después del commit, el evento se pierde (problema de *dual write*). La solución robusta es el **patrón transactional outbox**: guardar el evento en una tabla en la misma transacción y publicarlo con un proceso aparte con reintentos. Quedó fuera para respetar el alcance.

**Validación.** Probado manualmente con `docker compose`:
- una aprobada genera el log de publicación y el del consumidor;
- una rechazada no publica nada;
- con RabbitMQ detenido, la solicitud se aprueba igual (201 en ~6 ms) y el error queda en el log.

---

## 11. Frontend Angular

**Decisión.**
- Angular 22 con componentes **standalone**, **signals** para el estado y el nuevo control de flujo (`@if`, `@for`).
- Patrón **contenedor / presentacional**: `App` maneja el estado y las llamadas HTTP; `ApplicationForm`, `ApplicationResult` y `RecentApplications` solo reciben `input()` y emiten `output()`.
- `CreditApiService` tipado; detecta las respuestas repetidas por el header `Idempotent-Replayed`.
- Formularios reactivos con validación de forma únicamente (ver decisión 6).
- Muestra los casos 201, 200 (repetida), 409 (con la original), 400 (detalle por campo) y "backend no disponible".
- Proxy de desarrollo `/api → :8080` en lugar de CORS. Locale `es-CO` para formatear pesos.
- Sin librería de componentes: no se evalúa diseño gráfico y se mantiene liviano.

**Trade-offs.** No hay router (es una sola vista) ni manejo de estado global (NgRx), porque no se justifican. Para un despliegue real se serviría el build detrás del mismo dominio o se configuraría CORS.

---

## 12. Estrategia de pruebas

| Nivel | Clase | Qué demuestra |
|---|---|---|
| Unitaria (dominio) | `CreditPolicyTest` (14) | Reglas RF02 con valores límite |
| Caso de uso | `CreditApplicationServiceTest` (7) | Orquestación: aprobación + evento, rechazo sin reservar ni publicar, reserva fallida, replay, conflicto, carrera en el UNIQUE |
| Integración HTTP + BD | `CreditApplicationApiIntegrationTest` (8) | RF01-RF06 de punta a punta, códigos HTTP y efecto real sobre el cupo |
| Concurrencia | `ConcurrencyIntegrationTest` (2) | RF04 y RF05 simultáneos, deterministas |
| Frontend | Vitest (9) | Servicio HTTP (replay por header), render del resultado, manejo del 409 y del backend caído |

Validaciones adicionales:
- Ejecuté las pruebas de integración 50 veces seguidas (500 ejecuciones) sin fallos.
- Hice la prueba de mutación manual descrita en la decisión 3.
- Probé de punta a punta con el backend y `ng serve` reales, con curl a través del proxy y una captura de la UI.

---

## Mejoras con más tiempo

En orden de prioridad:

1. **PostgreSQL + Flyway** con `docker compose` para todo el stack. Permite varias instancias y R2DBC no bloqueante real.
2. **Transactional outbox** para garantizar la publicación de eventos, más reintentos y *dead letter queue* en el consumidor.
3. **Seguridad:** autenticación (OAuth2/JWT), autorización por canal y rate limiting.
4. **Observabilidad:** Actuator (health, incluido el de RabbitMQ), métricas de aprobadas/rechazadas, trazas y logs estructurados con un *correlation id*.
5. Mapear el timeout de lock a un **503 con `Retry-After`** en lugar de un 500.
6. **Paginación** por cursor (`processedAt`, `id`) en recientes y filtros por cliente o estado.
7. **OpenAPI/Swagger** para documentar el contrato.
8. **Testcontainers** para correr la integración contra PostgreSQL y RabbitMQ reales.
9. **E2E del frontend** (Playwright) y `Dockerfile` para backend y frontend.
10. Auditoría o historial de los conflictos 409 recibidos (hoy solo se responden).
