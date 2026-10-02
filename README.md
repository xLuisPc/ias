# BancoIAS · Core de aprobación de créditos

Solución full stack mínima para registrar, evaluar, persistir y consultar solicitudes de crédito.

- **Backend:** Java 21 · Spring Boot 4.1 WebFlux · Spring Data R2DBC · H2 (modo archivo)
- **Frontend:** Angular 22 (componentes standalone + signals)
- **Opcional:** RabbitMQ para publicar el evento de solicitud aprobada, con un consumidor de auditoría idempotente

Documentación complementaria:

- [docs/DECISIONES.md](docs/DECISIONES.md): registro de decisiones técnicas (contexto, alternativas, decisión, trade-offs y validación).
- [docs/USO_IA.md](docs/USO_IA.md): registro del uso de inteligencia artificial.

---

## 1. Alcance completado

| Requisito | Estado | Dónde / cómo verificarlo |
|---|---|---|
| RF01 Procesar solicitud y registrar fecha/hora | ✅ | `POST /api/credit-applications`; `processedAt` lo asigna el sistema (UTC) |
| RF02 Reglas de aprobación (monto > 0, plazo 6-60, cliente existe y está habilitado, cupo) | ✅ | `CreditPolicy` + reserva atómica de cupo. `CreditPolicyTest` |
| RF03 Conservar resultado y razón; el rechazo no consume cupo | ✅ | Tabla `credit_application`. `rejectedRequestDoesNotConsumeCredit` |
| RF04 Solicitudes simultáneas respetan el cupo | ✅ | `UPDATE` condicional atómico. `ConcurrencyIntegrationTest` |
| RF05 Referencias repetidas | ✅ | Igual contenido → 200 con resultado original; contenido distinto → 409. Restricción `UNIQUE` para la carrera simultánea |
| RF06 Consultar por referencia y recientes | ✅ | `GET /api/credit-applications/{ref}` y `GET /api/credit-applications?limit=20` |
| RF07 Interfaz Angular | ✅ | Formulario, resultado con la *línea de evaluación* (muestra en qué regla terminó la solicitud), consulta por referencia, recientes y cupo por cliente |
| Persistencia | ✅ | H2 en archivo (`backend/data/`): los datos sobreviven a reinicios |
| Pruebas automatizadas | ✅ | Backend: 37 (unitarias, caso de uso, integración HTTP, concurrencia y RabbitMQ real con Testcontainers). Frontend: 13 (Vitest) |
| Opcional RabbitMQ | ✅ | Deshabilitado por defecto. Publicación confirmada, consumidor idempotente, reintentos y *dead-letter queue*; ver sección 5 |

## 2. Requisitos

- Java 21 (el proyecto incluye Maven Wrapper, no hace falta instalar Maven)
- Node.js `^22.22.3 || ^24.15.0 || >=26` y npm (probado con Node 26.10)
- Docker, solo para el punto opcional de RabbitMQ y su prueba automatizada (sin Docker, esa prueba se omite)

## 3. Ejecución

### Backend (puerto 8080)

```bash
cd backend
./mvnw spring-boot:run
```

Al iniciar se crean las tablas y se cargan los clientes semilla (`schema.sql` y `data.sql`). Los datos quedan en `backend/data/`. Para empezar de cero, detener la aplicación y borrar esa carpeta.

### Frontend (puerto 4200)

```bash
cd frontend
npm install
npm start
```

Abrir http://localhost:4200. El servidor de desarrollo redirige `/api` hacia `http://localhost:8080` (`proxy.conf.json`), así que no se requiere configurar CORS.

### Pruebas

```bash
cd backend && ./mvnw test           # 37 pruebas (las 6 de RabbitMQ se omiten si no hay Docker)
cd frontend && npm test -- --watch=false   # 13 pruebas
```

## 4. API

| Método | Ruta | Respuestas |
|---|---|---|
| `POST` | `/api/credit-applications` | `201` solicitud nueva (aprobada **o** rechazada) · `200` + header `Idempotent-Replayed: true` si la referencia ya existía con los mismos datos · `409` si existía con datos diferentes · `400` si el payload es inválido |
| `GET` | `/api/credit-applications/{applicationReference}` | `200` · `404` |
| `GET` | `/api/credit-applications?limit=20` | `200`: lista de la más reciente a la más antigua (`limit` entre 1 y 100) |
| `GET` | `/api/customers` | `200`: clientes con cupo total, aprobado y disponible (solo lectura, para facilitar las pruebas) |

Los errores siguen el formato `application/problem+json` (RFC 9457).

```bash
# Aprobada
curl -i -X POST localhost:8080/api/credit-applications -H 'Content-Type: application/json' \
  -d '{"applicationReference":"REF-001","customerId":"CLI-1001","amount":6000000,"termMonths":24}'

# Misma referencia y mismos datos -> 200 con el resultado original
# Misma referencia con otro monto -> 409 con "originalApplication"
curl -i -X POST localhost:8080/api/credit-applications -H 'Content-Type: application/json' \
  -d '{"applicationReference":"REF-001","customerId":"CLI-1001","amount":7000000,"termMonths":24}'

curl localhost:8080/api/credit-applications/REF-001
curl 'localhost:8080/api/credit-applications?limit=10'
```

Ejemplo de respuesta:

```json
{
  "applicationReference": "REF-002",
  "customerId": "CLI-1001",
  "amount": 5000000.00,
  "termMonths": 24,
  "status": "REJECTED",
  "rejectionReason": "CREDIT_LIMIT_EXCEEDED",
  "message": "La solicitud supera el cupo máximo acumulado disponible del cliente",
  "processedAt": "2026-10-02T02:57:23.497049Z"
}
```

Razones de rechazo: `INVALID_AMOUNT`, `INVALID_TERM`, `CUSTOMER_NOT_FOUND`, `CUSTOMER_NOT_ELIGIBLE` y `CREDIT_LIMIT_EXCEEDED`.

## 5. Opcional: RabbitMQ

```bash
docker compose up -d rabbitmq
cd backend
APP_RABBITMQ_ENABLED=true ./mvnw spring-boot:run
```

Cuando una solicitud se aprueba, se publica `CreditApplicationApproved` en el exchange topic `credit.events` con la routing key `credit.application.approved`. Un consumidor de ejemplo, que simula un sistema de auditoría, lee la cola `credit.application.approved.audit` y guarda cada aprobación en la tabla `approved_application_audit`.

```
aprobada ─► credit.events (topic) ─► credit.application.approved.audit ─► consumidor ─► approved_application_audit
                                                  │ falla tras 3 intentos
                                                  ▼
                               credit.events.dlx ─► credit.application.approved.audit.dlq
```

| Aspecto | Comportamiento |
|---|---|
| Publicación | Después del commit y solo para aprobadas nuevas. El broker confirma cada mensaje (*publisher confirm*); si falla, se reintenta 2 veces |
| Duplicados | `messageId` = referencia de la solicitud. El consumidor lo usa como clave primaria: un mensaje repetido se ignora sin error |
| Errores del consumidor | 3 intentos con espera creciente (1 s y 2 s). Si siguen fallando, el mensaje pasa a la *dead-letter queue* en vez de reintentarse sin fin |

Para verlo, crear una solicitud aprobada desde la interfaz o con el `curl` de la sección 4. En el log aparecen `Evento CreditApplicationApproved publicado` y `[consumidor] Aprobacion registrada en auditoria`. La UI de administración está en http://localhost:15672 (`guest`/`guest`), pestaña *Queues*.

Si RabbitMQ no está disponible, la solicitud se procesa igual: la respuesta tarda entre 0,25 y 0,45 s más por los reintentos y el error queda en el log. `RabbitMessagingIntegrationTest` verifica todo lo anterior contra un RabbitMQ real (Testcontainers). Las decisiones están en la [decisión 10](docs/DECISIONES.md).

## 6. Estructura

```
backend/src/main/java/com/bancoias/credit
├── domain/            Modelo y reglas de negocio (Java puro, sin Spring)
├── application/       Caso de uso + puertos (interfaces de repositorios y eventos)
└── infrastructure/
    ├── persistence/   Adaptadores R2DBC (SQL explícito)
    ├── web/           Controladores REST, DTOs y manejo de errores
    ├── messaging/     RabbitMQ (opcional): publicador, consumidor de auditoría y publicador no-op por defecto
    └── config/        Reloj del sistema

frontend/src/app
├── core/              Modelos y servicio HTTP
├── components/        Componentes presentacionales (formulario, resultado, línea de evaluación, recientes, cupo)
└── app.ts             Componente contenedor (estado con signals)
```

## 7. Limitaciones y alcance pendiente

- **H2** es adecuada para el ejercicio, pero en modo archivo solo admite un proceso a la vez: no sirve para varias instancias. La lógica de concurrencia está en SQL estándar y funciona igual con PostgreSQL, que sería el siguiente paso.
- **Publicación del evento sin outbox.** Si RabbitMQ sigue caído después de los reintentos, o el proceso se cae justo después del commit, el evento se pierde (queda en el log). La solución robusta es el patrón *transactional outbox*.
- Los mensajes que terminan en la *dead-letter queue* no se reprocesan solos: se revisan y se reenvían desde la UI de administración de RabbitMQ.
- No hay autenticación ni autorización, migraciones versionadas (Flyway/Liquibase), observabilidad (Actuator/métricas) ni pruebas E2E del frontend.
- No hay administración de clientes; tampoco era requerida.
- El detalle de mejoras está en [docs/DECISIONES.md](docs/DECISIONES.md#mejoras-con-más-tiempo).
