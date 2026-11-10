-- los encargos de generación (ADR-0015): una lectura o un libro ilustrado que se escribe (y se ilustra) en segundo plano.
-- target STUDIO: termina en un borrador del estudio; PRIVATE: en una lectura a medida de quien la pidió (ADR-0016)
CREATE TABLE lesen.generation_job (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  actor           text NOT NULL,
  target          text NOT NULL CHECK (target IN ('STUDIO', 'PRIVATE')),
  format          text NOT NULL CHECK (format IN ('TEXT', 'PICTURE_BOOK')),
  request         jsonb NOT NULL,
  status          text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'WRITING', 'ILLUSTRATING', 'READY', 'FAILED')),
  -- lo que salió: el borrador del estudio o la lectura a medida
  draft_id        uuid REFERENCES lesen.studio_draft (id) ON DELETE SET NULL,
  reading_slug    text,
  -- por qué no salió (REFUSED, UNREADABLE, CHECKS, NO_WRITER, ERROR) y lo que encontró la comprobación
  reason          text,
  findings        jsonb,
  -- la biblia visual con que se ilustró, para volver a dibujar una página igual; quién dirigió el arte
  bible           jsonb,
  directed_by     text,
  -- lo que costó: tokens del texto e ilustraciones dibujadas
  usage           jsonb,
  attempts        integer NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  started_at      timestamptz,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  finished_at     timestamptz
);

CREATE INDEX generation_job_queue ON lesen.generation_job (created_at) WHERE status = 'QUEUED';
CREATE INDEX generation_job_owner ON lesen.generation_job (organization_id, user_id, created_at DESC);
CREATE INDEX generation_job_draft ON lesen.generation_job (draft_id) WHERE draft_id IS NOT NULL;

ALTER TABLE lesen.studio_event DROP CONSTRAINT studio_event_action_check;
ALTER TABLE lesen.studio_event ADD CONSTRAINT studio_event_action_check
  CHECK (action IN ('CREATED', 'GENERATED', 'SAVED', 'AUDIO_ATTACHED', 'AUDIO_REMOVED', 'SUBMITTED', 'RETURNED', 'PUBLISHED', 'DISCARDED'));
