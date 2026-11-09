CREATE SCHEMA IF NOT EXISTS lesen;

-- Catálogo de lecturas (ADR-0002, ADR-0003). El contenido editorial es de toda la instalación: no lleva organización.
-- Lo personal (perfil, progreso, vocabulario) sí, en V3.

CREATE TABLE lesen.language (
  code        text PRIMARY KEY CHECK (code ~ '^[a-z]{2,3}(-[A-Z]{2})?$'),
  native_name text NOT NULL,
  -- idioma en que la interfaz puede mostrarse (es, en) y/o idioma que se puede estudiar (de, en…)
  interface   boolean NOT NULL DEFAULT false,
  study       boolean NOT NULL DEFAULT false,
  position    integer NOT NULL DEFAULT 0
);

CREATE TABLE lesen.cefr_level (
  code             text PRIMARY KEY CHECK (code IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
  position         integer NOT NULL UNIQUE,
  -- palabras por minuto con que se estima la duración de una lectura de este nivel (ReadingTime)
  words_per_minute integer NOT NULL CHECK (words_per_minute > 0)
);

CREATE TABLE lesen.topic (
  slug        text PRIMARY KEY,              -- ruta completa: travel o travel/coasts
  parent_slug text REFERENCES lesen.topic (slug),
  labels      jsonb NOT NULL,                -- {"es": "...", "en": "..."}
  position    integer NOT NULL DEFAULT 0
);

CREATE TABLE lesen.reading (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug            text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  language        text NOT NULL REFERENCES lesen.language (code),
  level           text NOT NULL REFERENCES lesen.cefr_level (code),
  featured        boolean NOT NULL DEFAULT false,
  -- la versión publicada vigente; null mientras no hay ninguna
  current_version integer,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lesen.reading_topic (
  reading_id uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  topic_slug text NOT NULL REFERENCES lesen.topic (slug),
  position   integer NOT NULL DEFAULT 0,
  PRIMARY KEY (reading_id, topic_slug)
);

-- una versión publicada es inmutable (ADR-0003): el progreso, las notas y el vocabulario guardan el número de versión
-- que vio la persona y lo encuentran igual siempre
CREATE TABLE lesen.reading_version (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reading_id    uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  number        integer NOT NULL CHECK (number > 0),
  status        text NOT NULL CHECK (status IN ('PUBLISHED', 'RETIRED')),
  title         text NOT NULL,
  subtitle      text,
  summary       text NOT NULL,
  cover         text,
  accent        text,
  word_count    integer NOT NULL,
  minutes       integer NOT NULL,
  -- párrafos → oraciones → tokens con ids estables (ADR-0004)
  content       jsonb NOT NULL,
  content_hash  text NOT NULL,
  published_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (reading_id, number)
);

CREATE FUNCTION lesen.reading_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.content IS DISTINCT FROM OLD.content OR NEW.title IS DISTINCT FROM OLD.title
     OR NEW.subtitle IS DISTINCT FROM OLD.subtitle OR NEW.summary IS DISTINCT FROM OLD.summary
     OR NEW.number IS DISTINCT FROM OLD.number OR NEW.reading_id IS DISTINCT FROM OLD.reading_id
     OR NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
    RAISE EXCEPTION 'una versión publicada de una lectura es inmutable';
  END IF;
  IF OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED' THEN
    RAISE EXCEPTION 'una versión retirada no vuelve a publicarse';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER reading_version_immutable BEFORE UPDATE ON lesen.reading_version
  FOR EACH ROW EXECUTE FUNCTION lesen.reading_version_immutable();

-- traducciones contextuales por unidad, escritas por una persona (AUTHORED) o por un proveedor (PROVIDER, fase 6)
CREATE TABLE lesen.reading_translation (
  version_id uuid NOT NULL REFERENCES lesen.reading_version (id) ON DELETE CASCADE,
  unit       text NOT NULL CHECK (unit IN ('SENTENCE', 'PARAGRAPH')),
  ref        text NOT NULL,
  language   text NOT NULL REFERENCES lesen.language (code),
  text       text NOT NULL,
  source     text NOT NULL CHECK (source IN ('AUTHORED', 'PROVIDER')),
  PRIMARY KEY (version_id, unit, ref, language)
);

CREATE TABLE lesen.collection (
  slug         text PRIMARY KEY,
  labels       jsonb NOT NULL,
  descriptions jsonb NOT NULL,
  position     integer NOT NULL DEFAULT 0
);

CREATE TABLE lesen.collection_reading (
  collection_slug text NOT NULL REFERENCES lesen.collection (slug) ON DELETE CASCADE,
  reading_id      uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  position        integer NOT NULL DEFAULT 0,
  PRIMARY KEY (collection_slug, reading_id)
);

INSERT INTO lesen.language (code, native_name, interface, study, position) VALUES
  ('es', 'Español', true, false, 1),
  ('en', 'English', true, true, 2),
  ('de', 'Deutsch', false, true, 3);

-- estimación conservadora de lectura atenta en una lengua extranjera, por nivel (docs/architecture/domain-model.md)
INSERT INTO lesen.cefr_level (code, position, words_per_minute) VALUES
  ('A1', 1, 60), ('A2', 2, 75), ('B1', 3, 95), ('B2', 4, 115), ('C1', 5, 135), ('C2', 6, 150);
