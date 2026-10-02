# Registro de uso de inteligencia artificial

## Herramienta

Claude Code (Anthropic), un asistente de programación que corre en el editor y la terminal.

## En qué actividades se usó

| Actividad | Uso de IA |
|---|---|
| Análisis del enunciado y diseño de la solución | Propuesta de arquitectura, estrategia de concurrencia e idempotencia |
| Generación del esqueleto | Spring Initializr y Angular CLI ejecutados por el asistente |
| Implementación del backend y del frontend | Código generado por el asistente |
| Pruebas automatizadas | Diseño y escritura de las pruebas unitarias, de integración y de concurrencia |
| Documentación | README, registro de decisiones y este documento |

## Qué resultados se aprovecharon

Se aprovechó el código de backend y frontend, las pruebas y la documentación, después de revisarlos y validarlos como se describe abajo.

## Cómo se validaron

- **Compilación y pruebas:** 31 pruebas de backend y 9 de frontend en verde.
- **Pruebas de estabilidad:** las pruebas de integración se ejecutaron 50 veces seguidas (500 ejecuciones) sin fallos.
- **Prueba de mutación manual.** Se rompió a propósito la reserva atómica de cupo para confirmar que la prueba de concurrencia detecta el error. Ver la corrección 1.
- **Ejecución real:**
  - backend + `ng serve`;
  - solicitudes con curl a través del proxy cubriendo cada razón de rechazo, los replays (200) y los conflictos (409);
  - revisión visual de la interfaz;
  - punto de RabbitMQ con `docker compose`, incluido el escenario con el broker caído.
- **Revisión del código y de las decisiones** por parte del candidato antes de la entrega.

## Qué se corrigió o descartó

1. **La prueba de concurrencia inicial no demostraba nada.** Pasaba incluso con una implementación ingenua del cupo, porque con H2 en memoria las peticiones casi nunca se solapaban. Se rediseñó con un decorador que retrasa la lectura del cliente para forzar el solapamiento. Con eso, la prueba falla con la implementación ingenua (10 aprobadas en vez de 3) y pasa con la real.
2. **Prueba intermitente por precisión de fechas.** Comparar `processedAt` como texto fallaba a veces. Al investigar apareció un problema real: la fecha generada en Java tenía más precisión que la almacenada. Se corrigió en el código (truncado a microsegundos) y en la prueba (comparación de instantes).
3. **Stubbing incorrecto en Mockito.** `when(...).thenReturn(...)` sobre un mock ya configurado invocaba la respuesta anterior; se cambió por `doReturn(...)`.
4. **Dependencias y configuración generadas que no aplicaban:**
   - se eliminó `spring-boot-h2console`, que está pensado para servlet y no sirve en WebFlux;
   - se eliminaron los `.gitignore` por proyecto en favor de uno solo en la raíz;
   - se corrigió un patrón `.vscode/*` que solo aplicaba en la raíz.
5. **Formato inconsistente del monto** (`6000000` frente a `6000000.00`) entre una solicitud nueva y una repetida: se normalizó la escala al recibir la solicitud.
6. **Descartado por alcance:** patrón outbox, PostgreSQL, Swagger y Dockerfiles. Quedan documentados como mejoras.

## Consideraciones para evitar exponer información sensible

- Solo se usaron los datos ficticios del enunciado (clientes `CLI-xxxx` y montos de ejemplo). No se usaron datos reales de clientes ni de la organización.
- No se compartieron credenciales, tokens ni secretos con la herramienta. Las únicas credenciales del repositorio son las de desarrollo por defecto de RabbitMQ local (`guest`/`guest`).
- No se compartió código propietario de terceros; el repositorio se creó desde cero.
- El `.gitignore` excluye archivos `.env`, la base de datos local y los artefactos de compilación, para no subir información de entorno por error.
