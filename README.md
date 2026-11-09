# Lesen

Aprender idiomas leyendo historias que apetece leer: lecturas por nivel del MCER, cada palabra con su entrada de
diccionario en contexto, traducciones a petición, un cuaderno de vocabulario y el progreso de cada lectura.

Este repositorio es el **backend** (Kotlin, Spring Boot WebFlux, Wasichai). La interfaz es
[`wasichai/lesen-ui`](https://github.com/wasichai/lesen-ui).

## Arrancar en local

Requisitos: JDK 25, Docker (o un PostgreSQL 18 propio), Python 3, Node 26 y Yarn 1 (solo para formato y commits), y
un token de GitHub con `read:packages` para descargar Wasichai.

```bash
cp .env.example .env            # pon un WASICHAI_JWT_SECRET (openssl rand -base64 48)
docker compose up -d postgres
export GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)
./gradlew bootRun               # perfil dev: semilla de Wasichai y contenido de demostración
```

Con la aplicación en marcha, aplica el modelo de permisos y crea las personas de la demostración:

```bash
WASICHAI_EMAIL=admin@wasichai.local WASICHAI_PASSWORD=admin LESEN_DEMO_PASSWORD=<elige-una> \
  python3 model/apply.py --demo
```

Entra con `lena@demo.lesen.test` o `tomas@demo.lesen.test` y esa contraseña desde la interfaz (`yarn dev` en
`lesen-ui`, puerto 5190). La API escucha en el 8095; su OpenAPI está en `/api/lesen/public/openapi`.

## Pruebas

```bash
./gradlew build                 # ktlint, unitarias, arquitectura
./gradlew integrationTest       # PostgreSQL 18 en Testcontainers (o WASICHAI_TEST_DB_* hacia una base *_test)
python3 tools/check_demo.py     # el contenido de demostración y su cobertura de diccionario
python3 model/apply.py --validate-only
```

## Documentación

- Producto: [`docs/product/requirements.md`](docs/product/requirements.md), [`docs/product/roadmap.md`](docs/product/roadmap.md)
- Arquitectura: [`docs/architecture/overview.md`](docs/architecture/overview.md),
  [`domain-model.md`](docs/architecture/domain-model.md), [`api-design.md`](docs/architecture/api-design.md)
- Decisiones: [`docs/adr/`](docs/adr/)
- Contenido de demostración: [`docs/content/demo-content.md`](docs/content/demo-content.md)
- Historial de entregas: [`docs/HISTORY.md`](docs/HISTORY.md)

## Licencia

LGPL-3.0-or-later (`COPYING`, `LICENSE`).
