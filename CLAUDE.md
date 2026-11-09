# Lesen — backend

Aprender idiomas leyendo. Este repositorio es el backend: un monolito modular en Kotlin sobre la plataforma Wasichai.
La interfaz vive en `wasichai/lesen-ui`. Antes de cambiar algo, lee `docs/architecture/overview.md` y las ADR de
`docs/adr/`.

## Stack (no negociable sin ADR nueva)

Kotlin 2.4.21 · Spring Boot 4.1.2 WebFlux · Gradle 9.8.2 (wrapper, Kotlin DSL, `gradle/libs.versions.toml`) · JDK 25 ·
PostgreSQL 18 · Wasichai 1.10.0 (BOM y starter, de GitHub Packages) · Python 3 solo con la biblioteca estándar
(`model/apply.py`, `tools/check_demo.py`).

## Reglas

1. **Wasichai primero.** Identidad, login, roles, permisos, auditoría y organizaciones son de Wasichai. Nunca uses sus
   clases internas (`RecordStore`, `ObjectSchemaManager`, `AuditService`); solo su API pública (`CurrentUser`,
   `MetadataService`, `ModuleMigration`, sus excepciones). Lo que falte se pide aguas arriba.
2. **Reactivo de verdad.** Ni JPA, ni Hibernate, ni Spring Data, ni JDBC bloqueante en `src/main`. SQL con
   `DatabaseClient` y valores ligados. `ArchitectureTest` y `noServletStack` lo vigilan.
3. **Tablas propias en el esquema `lesen`** (ADR-0002), con Flyway vía `ModuleMigration`. Los objetos de
   `model/model.json` son solo anclas de permisos.
4. **Permisos en el servidor** con `Access` (`lesen.common`): `reader()`, `notebook()`, `writeNotebook()`. Nunca se
   compara un nombre de rol. Lo personal filtra siempre por la organización y el usuario del token; lo ajeno es 404.
5. **Lo publicado es inmutable** (ADR-0003): un cambio es una versión nueva; un disparador de la base lo garantiza.
6. **Nada inventado** (ADR-0005): sin entrada de diccionario o sin traducción, se dice; ningún proveedor finge éxito.
7. Paquete raíz `lesen`, un paquete por capacidad, con el grafo de `docs/architecture/overview.md` §3 (lo comprueba
   `ArchitectureTest`).
8. Sin sobreingeniería: una abstracción necesita un segundo uso concreto.

## Forma de trabajar

- Código y nombres técnicos en inglés; documentación, comentarios y mensajes de commit en español.
- `.editorconfig` manda (160 columnas). Antes de commitear: `./gradlew ktlintFormat` y `yarn format`.
- Conventional Commits (commitlint en el hook de husky). Una ADR nueva no se edita: se reemplaza con otra.
- Lo entregado va a `docs/HISTORY.md`, lo más nuevo arriba.
- Definition of Done: pruebas para lo nuevo (y una de autorización negativa si cambian permisos) y, ejecutados y en
  verde, `./gradlew build integrationTest`, `python3 model/apply.py --validate-only`, `python3 tools/check_demo.py` y
  `yarn format:check`.
- El token de GitHub Packages nunca va a un archivo: `GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)`.
- Ninguna prueba usa el 5432 de una base con datos: Testcontainers, o una base externa cuyo nombre acaba en `_test`
  (`WASICHAI_TEST_DB_*`).
