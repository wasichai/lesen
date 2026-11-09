-- Lo personal de cada lector: siempre dentro de su organización y de su usuario (los dos del token, nunca del cuerpo).
-- Nadie lee lo de otra persona: cada consulta filtra por (organization_id, user_id).

CREATE TABLE lesen.learner_profile (
  organization_id    uuid NOT NULL,
  user_id            uuid NOT NULL,
  interface_language text NOT NULL REFERENCES lesen.language (code),
  target_language    text NOT NULL REFERENCES lesen.language (code),
  level              text NOT NULL REFERENCES lesen.cefr_level (code),
  -- true si la persona eligió «no estoy segura»: el nivel es una estimación, no una medida
  level_estimated    boolean NOT NULL DEFAULT false,
  goals              text[] NOT NULL DEFAULT '{}',
  topics             text[] NOT NULL DEFAULT '{}',
  daily_minutes      integer NOT NULL CHECK (daily_minutes BETWEEN 5 AND 120),
  difficulty         text NOT NULL CHECK (difficulty IN ('COMFORTABLE', 'BALANCED', 'CHALLENGING')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, user_id),
  CHECK (interface_language <> target_language)
);

CREATE TABLE lesen.reading_progress (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  reading_id      uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  version_number  integer NOT NULL,
  -- id de la oración donde la persona estaba (p3s2)
  position        text,
  percent         integer NOT NULL DEFAULT 0 CHECK (percent BETWEEN 0 AND 100),
  seconds_read    integer NOT NULL DEFAULT 0 CHECK (seconds_read >= 0),
  started_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  completed_at    timestamptz,
  PRIMARY KEY (organization_id, user_id, reading_id)
);
CREATE INDEX reading_progress_recent ON lesen.reading_progress (organization_id, user_id, updated_at DESC);

CREATE TABLE lesen.reading_bookmark (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  reading_id      uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, user_id, reading_id)
);

CREATE TABLE lesen.reading_note (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  reading_id      uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  version_number  integer NOT NULL,
  sentence_ref    text NOT NULL,
  quote           text NOT NULL,
  note            text CHECK (char_length(note) <= 2000),
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reading_note_owner ON lesen.reading_note (organization_id, user_id, reading_id);

CREATE TABLE lesen.dictionary_search (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  query           text NOT NULL,
  entry_key       text,
  searched_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX dictionary_search_owner ON lesen.dictionary_search (organization_id, user_id, searched_at DESC);

CREATE TABLE lesen.dictionary_favorite (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  entry_key       text NOT NULL REFERENCES lesen.dictionary_entry (key) ON DELETE CASCADE,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, user_id, entry_key)
);

-- el cuaderno de vocabulario (fase 2: guardar y anotar). Las columnas de repaso espaciado llegan en la fase 4
CREATE TABLE lesen.vocabulary_entry (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id      uuid NOT NULL,
  user_id              uuid NOT NULL,
  entry_key            text NOT NULL REFERENCES lesen.dictionary_entry (key),
  language             text NOT NULL,
  translation_language text NOT NULL,
  surface              text NOT NULL,
  context              text,
  reading_id           uuid REFERENCES lesen.reading (id) ON DELETE SET NULL,
  version_number       integer,
  token_ref            text,
  status               text NOT NULL CHECK (status IN ('SAVED', 'LEARNING', 'FAMILIAR', 'MASTERED')),
  note                 text CHECK (char_length(note) <= 2000),
  saved_at             timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, user_id, entry_key)
);
CREATE INDEX vocabulary_entry_owner ON lesen.vocabulary_entry (organization_id, user_id, saved_at DESC);
