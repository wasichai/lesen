# Operación

Lo necesario para poner Lesen en marcha, mantenerlo y recuperarlo. Lo de la plataforma (login, usuarios, roles,
auditoría, almacén de archivos) es de Wasichai y sigue su documentación.

## Despliegue

Un solo proceso (`build/libs/lesen.jar`, JDK 25) con PostgreSQL 18 y, para el estudio, un almacén de archivos. La
interfaz (`lesen-ui`) se sirve aparte y llama a `/api`.

| Variable                                                       | Para qué                                                                                               |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `WASICHAI_DB_HOST`, `_PORT`, `_NAME`, `_USERNAME`, `_PASSWORD` | La base de datos                                                                                       |
| `WASICHAI_JWT_SECRET`                                          | Firma de los tokens; sin ella no arranca (`openssl rand -base64 48`)                                   |
| `WASICHAI_CORS_ALLOWED_ORIGINS`                                | Orígenes de navegador ajenos, si la interfaz vive en otro dominio                                      |
| `LESEN_FILES_PATH`                                             | Carpeta del almacén local de archivos (`./data/files` por defecto)                                     |
| `WASICHAI_FILES_STORE=s3` y `WASICHAI_FILES_S3_*`              | El almacén en S3 en lugar de disco (configuración de `wasichai-files`)                                 |
| `LESEN_DEMO_ENABLED`                                           | `true` publica el contenido de demostración al arrancar (idempotente)                                  |
| `ANTHROPIC_API_KEY`                                            | La corrección asistida de respuestas abiertas (ADR-0009); sin ella, autoevaluación                     |
| `LESEN_AI_MODEL`                                               | El modelo de Claude (`claude-opus-5-5` por defecto)                                                    |
| `LESEN_GENERATION_EFFORT`                                      | El esfuerzo del modelo en las lecturas a medida (ADR-0013), `medium` por defecto                       |
| `LESEN_GENERATION_WORKERS`                                     | Encargos que corren a la vez (ADR-0015), 1 por defecto                                                 |
| `LESEN_GENERATION_QUOTA_DAILY` / `_MONTHLY_BOOKS`              | Lecturas a medida por persona y día (3) y libros ilustrados por mes (4) (ADR-0016)                     |
| `LESEN_ILLUSTRATION_PROVIDER`                                  | `none` (por defecto: ilustraciones pendientes), `fake`, `openai` o `gemini` (ADR-0015)                 |
| `LESEN_ILLUSTRATION_MODEL`                                     | El modelo de imagen; vacío: `gpt-image-1` o `gemini-2.5-flash-image`                                   |
| `OPENAI_API_KEY` / `GEMINI_API_KEY`                            | La clave del proveedor de imágenes elegido; nunca en un archivo                                        |
| `LESEN_ILLUSTRATION_AGENT`                                     | `true`: con `ANTHROPIC_API_KEY`, Embabel y Claude dirigen el arte; `false`, la biblia de la generación |
| `LESEN_ILLUSTRATION_MAX_RETRIES`                               | Veces que se vuelve a dibujar una página que la revisión rechaza (1)                                   |
| `PORT`                                                         | Puerto HTTP (8095 por defecto)                                                                         |

Las migraciones (Flyway por módulo: las de Wasichai y las de `db/lesen`) corren al arrancar. Después, con la
aplicación en marcha, `model/apply.py` crea las anclas de permisos (`lectura`, `cuaderno`, `estudio` y su acción
`PUBLICAR`) y los roles `LECTOR`, `REDACTOR` y `EDITOR`; es idempotente y se vuelve a ejecutar en cada despliegue:

```bash
WASICHAI_EMAIL=… WASICHAI_PASSWORD=… python3 model/apply.py --url https://lesen.example
```

Las personas se dan de alta en la administración de Wasichai (`/api/users`) con su rol: `LECTOR` para leer, `REDACTOR`
para escribir borradores y `EDITOR` para revisarlos y publicarlos.

## Respaldo y recuperación

Lo que no se puede volver a generar vive en dos sitios y se respalda junto:

- **La base**: los esquemas de Wasichai y `lesen` (`pg_dump` del conjunto). Incluye el contenido publicado, los
  borradores y su historial y todo lo personal de cada lector.
- **El almacén de archivos**: el audio y las portadas subidos desde el estudio (`LESEN_FILES_PATH`, o el bucket). Cada
  archivo tiene su fila en `lesen.media` con su huella SHA-256: tras restaurar, un archivo sin bytes da 404 y su huella
  permite comprobar que el que vuelve es el mismo.

Lo de la demostración está en el classpath y se vuelve a publicar con `LESEN_DEMO_ENABLED=true`.

## Vigilancia

- `GET /actuator/health`, `/actuator/health/liveness` y `/actuator/health/readiness` para el orquestador o el
  balanceador (con la demostración activada, `readiness` espera a que termine de publicarse). Actuator no expone el
  entorno, las propiedades ni volcados de memoria.
- Los avisos que merecen atención salen en el registro: formas sin entrada al publicar, audio con palabras sin tiempo,
  correcciones asistidas que fallan y se reintentan.
- El contrato de la API está en `/api/lesen/public/openapi`.

## Contenido

El estudio (`lesen-ui`, `/studio`) escribe, comprueba y publica lecturas (ADR-0012). Publicar nunca cambia una versión
anterior (ADR-0003): quien la empezó la sigue leyendo igual, y el historial de cada lectura dice quién publicó cada
versión y cuándo. Para retirar una lectura de la portada, se publica sin destacar; para corregirla, se edita y se
publica una versión nueva.
