-- Fase 4: comprensión (ADR-0009), encuentros y repaso espaciado (ADR-0010).

-- las preguntas de una versión concreta: los ids de oración y palabra que citan son los de esa versión. Se guardan como
-- un documento (questions), con la huella del origen para cargarlas de forma idempotente
CREATE TABLE lesen.assessment (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  version_id  uuid NOT NULL UNIQUE REFERENCES lesen.reading_version (id) ON DELETE CASCADE,
  questions   jsonb NOT NULL,
  fingerprint text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lesen.assessment_attempt (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  assessment_id   uuid NOT NULL REFERENCES lesen.assessment (id) ON DELETE CASCADE,
  -- el idioma de interfaz en que se respondió: las explicaciones y la corrección asistida van en él
  language        text NOT NULL,
  submitted_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX assessment_attempt_owner ON lesen.assessment_attempt (organization_id, user_id, assessment_id, submitted_at DESC);

-- una respuesta por pregunta del intento. PENDING: la corrige el asistente (cola de trabajos). UNGRADED: abierta y sin
-- asistente; la persona se autoevalúa con la respuesta modelo a la vista
CREATE TABLE lesen.assessment_answer (
  attempt_id    uuid NOT NULL REFERENCES lesen.assessment_attempt (id) ON DELETE CASCADE,
  question_id   text NOT NULL,
  question_type text NOT NULL,
  response      jsonb NOT NULL,
  result        text NOT NULL CHECK (result IN ('CORRECT', 'PARTIAL', 'INCORRECT', 'PENDING', 'UNGRADED')),
  evaluator     text CHECK (evaluator IN ('RULE', 'AI', 'SELF')),
  justification text,
  citations     text[] NOT NULL DEFAULT '{}',
  evaluated_at  timestamptz,
  PRIMARY KEY (attempt_id, question_id)
);

-- la cola de la corrección asistida: un trabajo por respuesta abierta, tomado con FOR UPDATE SKIP LOCKED
CREATE TABLE lesen.evaluation_job (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  attempt_id      uuid NOT NULL,
  question_id     text NOT NULL,
  status          text NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
  tries           integer NOT NULL DEFAULT 0,
  run_after       timestamptz NOT NULL DEFAULT now(),
  locked_at       timestamptz,
  error           text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (attempt_id, question_id) REFERENCES lesen.assessment_answer (attempt_id, question_id) ON DELETE CASCADE,
  UNIQUE (attempt_id, question_id)
);
CREATE INDEX evaluation_job_queue ON lesen.evaluation_job (status, run_after) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX evaluation_job_owner ON lesen.evaluation_job (organization_id, user_id, created_at DESC);

-- cada palabra vista al terminar una lectura: ver no es recordar, y no cambia el estado del cuaderno
CREATE TABLE lesen.vocabulary_encounter (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  entry_key       text NOT NULL REFERENCES lesen.dictionary_entry (key) ON DELETE CASCADE,
  language        text NOT NULL,
  reading_id      uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  version_number  integer NOT NULL,
  -- la primera aparición en esa versión
  token_ref       text NOT NULL,
  encountered_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, user_id, entry_key, reading_id, version_number)
);
CREATE INDEX vocabulary_encounter_language ON lesen.vocabulary_encounter (organization_id, user_id, language);

-- la tarjeta de repaso FSRS de cada palabra guardada (ADR-0010). due_at es la próxima fecha; una palabra recién guardada
-- se puede repasar ya
ALTER TABLE lesen.vocabulary_entry
  ADD COLUMN card_state     text NOT NULL DEFAULT 'LEARNING' CHECK (card_state IN ('LEARNING', 'REVIEW', 'RELEARNING')),
  ADD COLUMN card_step      integer DEFAULT 0,
  ADD COLUMN stability      double precision,
  ADD COLUMN difficulty     double precision,
  ADD COLUMN due_at         timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN last_review_at timestamptz,
  ADD COLUMN reviews        integer NOT NULL DEFAULT 0,
  ADD COLUMN lapses         integer NOT NULL DEFAULT 0;
UPDATE lesen.vocabulary_entry SET due_at = saved_at;
CREATE INDEX vocabulary_entry_due ON lesen.vocabulary_entry (organization_id, user_id, due_at);

-- cada respuesta de un repaso, idempotente por el id que pone el cliente
CREATE TABLE lesen.vocabulary_review (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id  uuid NOT NULL,
  user_id          uuid NOT NULL,
  vocabulary_id    uuid NOT NULL REFERENCES lesen.vocabulary_entry (id) ON DELETE CASCADE,
  client_id        text NOT NULL,
  rating           smallint NOT NULL CHECK (rating BETWEEN 1 AND 4),
  reviewed_at      timestamptz NOT NULL,
  elapsed_days     integer,
  stability        double precision NOT NULL,
  difficulty       double precision NOT NULL,
  due_at           timestamptz NOT NULL,
  status_after     text NOT NULL,
  UNIQUE (organization_id, user_id, client_id)
);
CREATE INDEX vocabulary_review_owner ON lesen.vocabulary_review (organization_id, user_id, reviewed_at DESC);

-- corrección de V4: dos versiones con el mismo texto (cambia solo el título, por ejemplo) comparten el mismo archivo de
-- audio, cada una con sus tiempos. El archivo no es único; lo único es (versión, voz)
ALTER TABLE lesen.audio_asset DROP CONSTRAINT audio_asset_file_key;
