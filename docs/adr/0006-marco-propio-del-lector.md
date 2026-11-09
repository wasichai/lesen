# ADR-0006 — Marco propio de quien lee sobre rutas bare

Estado: aceptada (2026-11-09)

## Contexto

El marco de `@wasichai/core` es una barra lateral con cajón, pensado para administración. El producto pide un marco
de aplicación de consumo (barra superior en escritorio, barra de pestañas abajo en el móvil) y un lector sin marco.
`@wasichai/ui` no tiene popover, hoja inferior ni barra de pestañas.

## Decisión

- Una sola `WasichaiApp` (un router, el login y la sesión de core). Todas las rutas de Lesen son `chrome: 'bare'` y se
  envuelven en `LearnerLayout` (`lesen-ui/src/shared`); el lector va sin él.
- Las hojas (detalle de palabra, ajustes, notas) usan el `Dialog` de `@wasichai/ui` (foco atrapado, Escape, bloqueo de
  scroll compatible con CSP) colocado abajo o a la derecha. El vistazo al pasar el cursor es una tarjeta propia, sin
  dependencia nueva: no atrapa el foco y el teclado usa la hoja.
- Se pedirán aguas arriba un popover, una hoja inferior y una barra de pestañas; cuando existan, se reemplazan.

## Consecuencias

- Las pantallas de administración de core siguen disponibles para quien administra.
- Lesen mantiene unos pocos componentes de marco propios, probados en su repositorio.
