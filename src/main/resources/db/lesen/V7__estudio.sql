-- Fase 6: estudio editorial (ADR-0012). El contenido editorial es de toda la instalación (ADR-0002): los borradores no
-- llevan organización; quién hizo cada cosa queda en el historial.

-- los archivos subidos desde el estudio (audio y portadas). Los bytes viven en el almacén de archivos de Wasichai
-- (FileStore: disco local o S3) bajo store_key; aquí, lo que son. El nombre público sale de la huella: subir dos veces
-- el mismo archivo no lo guarda dos veces
CREATE TABLE lesen.media (
  file         text PRIMARY KEY CHECK (file ~ '^[a-z0-9]+(-[a-z0-9]+)*\.(mp3|ogg|m4a|png|jpg|webp)$'),
  kind         text NOT NULL CHECK (kind IN ('AUDIO', 'COVER')),
  content_type text NOT NULL,
  size_bytes   bigint NOT NULL CHECK (size_bytes > 0),
  sha256       text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  store_key    text NOT NULL UNIQUE,
  created_by   text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);

-- un borrador: el documento de la lectura (la misma forma que publica ReadingPublisher), sus preguntas y su audio.
-- revision crece con cada guardado: dos personas editando a la vez no se pisan (409)
CREATE TABLE lesen.studio_draft (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug              text NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  language          text NOT NULL REFERENCES lesen.language (code),
  -- la lectura y la versión de la que salió; null si es una lectura nueva
  reading_id        uuid REFERENCES lesen.reading (id) ON DELETE SET NULL,
  base_version      integer,
  status            text NOT NULL CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'DISCARDED')),
  document          jsonb NOT NULL,
  questions         jsonb NOT NULL DEFAULT '[]',
  audio             jsonb,
  published_version integer,
  revision          integer NOT NULL DEFAULT 1,
  created_by        text NOT NULL,
  updated_by        text NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
-- un solo borrador abierto por lectura
CREATE UNIQUE INDEX studio_draft_open ON lesen.studio_draft (slug) WHERE status IN ('DRAFT', 'IN_REVIEW');
CREATE INDEX studio_draft_recent ON lesen.studio_draft (updated_at DESC);

-- el historial editorial: quién hizo qué y cuándo. Solo inserciones
CREATE TABLE lesen.studio_event (
  id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  draft_id uuid NOT NULL REFERENCES lesen.studio_draft (id) ON DELETE CASCADE,
  slug     text NOT NULL,
  action   text NOT NULL CHECK (action IN ('CREATED', 'SAVED', 'AUDIO_ATTACHED', 'AUDIO_REMOVED', 'SUBMITTED', 'RETURNED', 'PUBLISHED',
                                           'DISCARDED')),
  actor    text NOT NULL,
  note     text CHECK (char_length(note) <= 2000),
  version  integer,
  at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX studio_event_slug ON lesen.studio_event (slug, at DESC);
CREATE INDEX studio_event_draft ON lesen.studio_event (draft_id, at DESC);

CREATE FUNCTION lesen.studio_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'studio_event is append-only';
END $$;
CREATE TRIGGER studio_event_append_only BEFORE UPDATE ON lesen.studio_event
  FOR EACH ROW EXECUTE FUNCTION lesen.studio_event_append_only();
