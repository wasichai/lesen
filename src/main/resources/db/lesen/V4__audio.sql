-- Audio y sincronización (ADR-0008). Los tiempos van aparte del archivo y se anclan a los ids de token de una versión
-- inmutable (ADR-0003): una versión nueva pide audio o alineación nuevos, nunca se reescriben los de otra.

CREATE TABLE lesen.audio_voice (
  id       text PRIMARY KEY,              -- espeak-ng:de
  provider text NOT NULL,
  language text NOT NULL REFERENCES lesen.language (code),
  name     text NOT NULL,
  license  text NOT NULL
);

CREATE TABLE lesen.audio_asset (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  version_id  uuid NOT NULL REFERENCES lesen.reading_version (id) ON DELETE CASCADE,
  voice_id    text NOT NULL REFERENCES lesen.audio_voice (id),
  file        text NOT NULL UNIQUE CHECK (file ~ '^[a-z0-9]+(-[a-z0-9]+)*\.(mp3|ogg|m4a)$'),
  format      text NOT NULL,
  duration_ms integer NOT NULL CHECK (duration_ms > 0),
  -- GENERATED: síntesis con sus marcas; UPLOADED: audio y tiempos subidos por una persona (fase 6)
  origin      text NOT NULL CHECK (origin IN ('GENERATED', 'UPLOADED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (version_id, voice_id)
);

CREATE TABLE lesen.word_timing (
  asset_id uuid NOT NULL REFERENCES lesen.audio_asset (id) ON DELETE CASCADE,
  token_id text NOT NULL,
  start_ms integer NOT NULL CHECK (start_ms >= 0),
  end_ms   integer NOT NULL,
  PRIMARY KEY (asset_id, token_id),
  CHECK (end_ms > start_ms)
);

-- lo que la validación encontró al publicar: palabras sin tiempo (lo dicho y lo escrito no casan) y avisos
CREATE TABLE lesen.audio_alignment_report (
  asset_id  uuid PRIMARY KEY REFERENCES lesen.audio_asset (id) ON DELETE CASCADE,
  unaligned text[] NOT NULL DEFAULT '{}',
  warnings  text[] NOT NULL DEFAULT '{}'
);
