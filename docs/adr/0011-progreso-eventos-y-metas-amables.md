# ADR-0011 — Progreso: eventos, proyección diaria y metas que no castigan

Estado: aceptada (2026-11-09)

## Contexto

La fase 5 pide un panel de lectura, vocabulario y comprensión, metas, rachas, logros y una recomendación que aprenda del
historial. Lo que cuenta lo hacen `library` (leer, terminar), `vocabulary` (guardar, repasar) y `assessment` (responder
preguntas). Si `progress` leyera sus tablas o ellas le llamaran, el grafo de capacidades tendría ciclos o lecturas
cruzadas por todas partes. Y un panel de progreso puede convertirse en una máquina de culpa: rachas rotas, días en rojo,
cifras que parecen medidas y son suposiciones.

## Decisión

- **Eventos en un registro solo de inserciones.** Quien emite solo conoce `LearningActivityListener` (en `common`);
  `progress` la implementa. Seis tipos: empezar, avanzar (los segundos nuevos sobre el total ya contado), terminar (con
  las palabras de la versión), guardar palabra, repasar palabra y enviar un cuestionario. Lo repetible lleva una clave
  (`started:`, `completed:`, `saved:`, `review:<clientId>`, `attempt:`) y cuenta una vez; un disparador impide cambiar
  un evento. V6 rellena el registro con lo que ya guardaban las tablas de las fases anteriores.
- **Proyección diaria reconstruible.** `daily_progress` suma los eventos por día local (en la zona horaria de la persona)
  e idioma. Lo incremental y la reconstrucción usan las mismas funciones puras (`Projection`); cambiar de zona horaria
  reconstruye desde los eventos.
- **Medido o estimado en cada cifra.** Lo contado (minutos, lecturas, palabras guardadas y encontradas, respuestas) es
  `MEASURED`; lo inferido sobre lo que la persona sabe o leyó (palabras leídas, aprendiendo, dominadas, nivel MCER) es
  `ESTIMATED`. El nivel siempre es estimado.
- **Metas amables.** La meta diaria (minutos del perfil) solo dice cuánto se lleva. La racha es semanal: la persona elige
  cuántos días quiere leer (1–7) y los demás son de descanso; la semana en curso solo puede sumar. No hay ningún campo de
  «días perdidos» ni «racha rota»; al saltarse una semana se empieza otra racha y se conservan la mejor y las semanas
  cumplidas.
- **Logros como datos.** `achievement` dice qué se cuenta y desde cuánto; las reglas son deterministas, se conceden una
  vez y no se retiran.
- **Comprensión honesta.** Solo cuenta el primer intento de cada cuestionario y solo lo ya corregido (lo parcial vale
  medio). Con 8 o más respuestas en su nivel, una media desde 85 sugiere subir y una por debajo de 50, bajar.
- **Recomendación v1.** `catalog` define `LearnerSignalsSource` y `progress` la implementa: la banda de nivel se mueve
  con esa sugerencia (`COMPREHENSION`), suman los temas de lo terminado (`HISTORY`) y lo que la organización terminó en
  los últimos 7 días (`TRENDING`, un recuento sin nombres). Cada recomendación sigue diciendo por qué.
- **Sin avisos fuera de la aplicación.** El starter de Wasichai 1.10.0 no publica una API de notificaciones; el resumen
  semanal es una pantalla y, si la persona lo activa, un aviso dentro de la aplicación. Sin correo.

## Consecuencias

- `progress` depende de `common`, `learner`, `catalog`, `vocabulary` y `assessment`; nadie depende de `progress`.
- El tiempo de lectura anterior a V6 no se sabe por día: cuenta entero el día de su última actualización.
- Los logros de minutos o días activos se conceden con el siguiente evento que no sea de avance o al abrir el panel.
- Las estadísticas de vocabulario (`vocabulary/stats`) siguen contando «hoy» en UTC; el panel ya usa el día local.
