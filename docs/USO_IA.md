# Uso de inteligencia artificial

> **En resumen.** Desarrollé la solución con un agente de programación con IA (Claude Code, de Anthropic) como acelerador. El agente escribió el código siguiendo el alcance y los criterios que le definí. El valor que aporté estuvo en dirigir, en exigir evidencia y en entender cada decisión: **nada se aceptó por confianza, todo se aceptó por evidencia.** Este documento muestra cómo se controló la calidad de lo generado y dónde se equivocó la IA.

---

## 1. Cómo se repartió el trabajo

| Etapa | Qué aportó la IA | Control aplicado |
|---|---|---|
| **Alcance** | Traducir el enunciado en tareas concretas | Alcance definido de antemano: cumplir RF01-RF07 sin sobreingeniería, un solo extra con valor (RabbitMQ) y trazabilidad de decisiones |
| **Diseño** | Proponer alternativas para concurrencia, idempotencia, persistencia y API, con sus trade-offs | Cada decisión quedó registrada con alternativas, riesgos y forma de validación ([DECISIONES.md](DECISIONES.md)), para poder defenderla |
| **Implementación** | Generar el código del backend, del frontend y la configuración | Revisión del código y verificación de que cada requisito tiene una prueba que lo respalde |
| **Pruebas** | Escribir pruebas unitarias, de integración y de concurrencia | Las pruebas se ejecutaron, se repitieron y se sometieron a mutación (sección 3) |
| **Documentación** | Redactar el README y los registros | Contraste contra el código real y el resultado de las pruebas |

---

## 2. Principios que guiaron el uso de IA

1. **Evidencia antes que confianza.** Ningún resultado se dio por bueno sin compilarlo, ejecutarlo y verlo funcionar.
2. **Un test que no puede fallar no prueba nada.** Las pruebas críticas se validaron rompiendo el código a propósito.
3. **Alcance acotado.** La IA tiende a agregar de más. Se descartó todo lo que no aportaba al objetivo de la prueba (sección 5).
4. **Entender antes de entregar.** Cada decisión está documentada para poder explicarla y defenderla sin depender de la herramienta.
5. **Cero datos sensibles.** Solo se usaron los datos ficticios del enunciado (sección 6).

---

## 3. Validación aplicada

| Validación | Qué se verificó | Resultado |
|---|---|---|
| Suite automatizada | Reglas, caso de uso, API, concurrencia y frontend | ✅ 31 pruebas de backend + 9 de frontend |
| Estabilidad | Pruebas de integración ejecutadas 50 veces seguidas | ✅ 500/500 ejecuciones sin fallos |
| **Mutación** | Romper a propósito la reserva atómica de cupo, para confirmar que la prueba de concurrencia lo detecta | ✅ La prueba falla con el código roto (10 aprobadas en vez de 3) y pasa con el real |
| Ejecución real | Backend + Angular con curl a través del proxy: cada razón de rechazo, replay (200) y conflicto (409) | ✅ |
| Interfaz | Revisión visual de la UI contra el backend real | ✅ |
| RabbitMQ | Publicación y consumo con `docker compose`, incluido el broker caído | ✅ La aprobación no depende del broker |

---

## 4. Dónde se equivocó la IA y cómo se detectó

Esta es la parte más importante: muestra por qué la IA **no** se usó en piloto automático.

| # | Error de lo generado | Cómo se detectó | Corrección |
|---|---|---|---|
| 1 | **La prueba de concurrencia era un falso positivo.** Pasaba incluso con una implementación incorrecta del cupo, porque con H2 en memoria las peticiones casi nunca se solapaban. | Prueba de mutación: con la protección eliminada, el test seguía en verde. | Se rediseñó para que fuera determinista: un decorador retrasa la lectura del cliente y una barrera libera las peticiones a la vez. Ahora detecta el error. |
| 2 | **Prueba intermitente que escondía un bug real.** La fecha generada en Java tenía más precisión que la almacenada en la base de datos, así que la respuesta y lo persistido podían diferir. | Repetición masiva de las pruebas: 7 fallos en 400 ejecuciones. | Se truncó la fecha a microsegundos en el código y la prueba pasó a comparar instantes en lugar de texto. |
| 3 | **Uso incorrecto de Mockito** al reconfigurar un mock ya configurado. | Falló la ejecución de la suite. | Se usó `doReturn(...)`. |
| 4 | **Configuración generada que no aplicaba:** una dependencia pensada para servlet (no sirve en WebFlux), `.gitignore` duplicados y un patrón que solo aplicaba en la raíz. | Revisión de dependencias y de los archivos versionados (`git ls-files`, `git check-ignore`). | Se eliminó la dependencia y quedó un solo `.gitignore` en la raíz, verificado. |
| 5 | **Monto con formato inconsistente** (`6000000` frente a `6000000.00`) entre una solicitud nueva y una repetida. | Pruebas manuales con curl. | Se normalizó la escala al recibir la solicitud. |

> Lección: la IA genera código plausible muy rápido, pero **"plausible" no es "correcto"**. Los dos errores más serios (1 y 2) pasaban todas las pruebas normales y solo aparecieron al desconfiar de los resultados en verde.

---

## 5. Qué se descartó a propósito

La IA puede proponer más de lo necesario. Para respetar el esfuerzo estimado, quedaron fuera (documentados como mejoras en [DECISIONES.md](DECISIONES.md#mejoras-con-más-tiempo)):

- Patrón *transactional outbox* para los eventos
- PostgreSQL + Flyway y Dockerfiles para todo el stack
- Swagger/OpenAPI, seguridad y observabilidad

---

## 6. Protección de información sensible

- **Datos:** solo se usaron los datos ficticios del enunciado (clientes `CLI-xxxx`, montos de ejemplo). No se compartieron datos reales de clientes ni de ninguna organización.
- **Secretos:** no se compartieron credenciales, tokens ni llaves con la herramienta. Las únicas credenciales del repositorio son las de desarrollo por defecto de RabbitMQ local (`guest`/`guest`).
- **Código:** no se compartió código propietario de terceros; el repositorio se creó desde cero.
- **Repositorio:** el `.gitignore` excluye `.env`, la base de datos local y los artefactos de compilación, para no subir información de entorno por error.

---

## 7. Cómo llevaría este enfoque a un equipo

- Tratar el código generado por IA igual que el de cualquier colaborador: **revisión obligatoria** antes de integrarlo.
- Exigir que las reglas críticas (dinero, concurrencia, idempotencia) tengan pruebas que **demuestren que pueden fallar**.
- Mantener un registro de decisiones para que el conocimiento quede en el equipo y no en la herramienta.
- No compartir con herramientas externas datos productivos ni secretos, y preferir entornos con políticas de privacidad aprobadas por la organización.
