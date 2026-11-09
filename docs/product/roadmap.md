# Hoja de ruta

Cada fase termina con algo que una persona puede usar de principio a fin, probado y documentado. Ninguna fase se da
por terminada porque una pantalla exista.

| Fase | Contenido                                                                                                   | Criterio de salida                                                                                         | Estado    |
| ---- | ----------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | --------- |
| 0    | Auditoría de elearning-backend, elearning-frontend y Wasichai; arquitectura; ADR                            | Este documento, `requirements.md`, `overview.md`, `domain-model.md`, `api-design.md` y ADR 0001–0007       | Hecha     |
| 1    | Andamiaje, sistema de diseño, onboarding, perfil, portada, catálogo, ficha, contenido demo                  | Una persona nueva completa el onboarding y encuentra una lectura de su nivel en el móvil y en escritorio   | Hecha     |
| 2    | Lector inmersivo, diccionario en contexto, traducciones, notas, posición, biblioteca, cuaderno básico       | Recorrido completo de leer → buscar palabra → guardarla → terminar → verla en el cuaderno, con pruebas e2e | Hecha     |
| 3    | Audio: activos, voces, tiempos por palabra, reproductor, resaltado sincronizado, fallos                     | Audio real de demostración con tiempos verificados contra el texto; el lector sin audio sigue igual        | Hecha     |
| 4    | Comprensión (diez tipos de pregunta, evaluación determinista y asistida), repaso espaciado FSRS, encuentros | Una lectura con preguntas evaluadas y explicadas; un repaso que cambia la próxima fecha de cada palabra    | Hecha     |
| 5    | Panel de progreso, metas, rachas amables, logros, recomendación con historial, colecciones propias          | El panel separa lo medido de lo estimado y ninguna métrica castiga un día sin leer                         | En curso  |
| 6    | Estudio editorial, gestión de traducciones y audio, validación, publicación, operación                      | Una editora publica una lectura nueva con audio y preguntas sin tocar JSON; versiones e historial          | Pendiente |

## Próximos pasos concretos

- **Fase 5.** Backend hecho (ADR-0011): actividad, proyección diaria, panel, resumen semanal, metas, logros, listas
  propias y recomendación v1; falta la interfaz: panel, metas en el perfil, listas en Mis lecturas y en la ficha, y lo
  que se lee esta semana en Descubrir.
- **Aguas arriba.** Pedir a `wasichai-ui` un popover, una hoja inferior y una barra de pestañas (ADR-0006), y a
  `wasichai` un registro público de cuentas con rol por defecto y una API pública de notificaciones (ADR-0011).
