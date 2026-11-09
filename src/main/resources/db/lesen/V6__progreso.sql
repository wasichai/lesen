-- Fase 5: progreso, metas amables, logros y listas personales (ADR-0011).

-- lo que hizo cada persona, un evento por fila y solo con inserciones. dedup_key hace idempotente lo que puede repetirse
-- (empezar o terminar una lectura, guardar o repasar una palabra, enviar un intento); el avance de lectura no la lleva
-- porque solo se registra lo que suma (los segundos nuevos sobre el total que ya estaba)
CREATE TABLE lesen.learning_activity (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  type            text NOT NULL CHECK (type IN ('READING_STARTED', 'PROGRESS', 'READING_COMPLETED', 'WORD_SAVED', 'WORD_REVIEWED',
                                                'ASSESSMENT_SUBMITTED')),
  occurred_at     timestamptz NOT NULL,
  language        text NOT NULL,
  -- sin clave foránea: un evento no cambia nunca, ni siquiera si la lectura desaparece
  reading_id      uuid,
  seconds         integer NOT NULL DEFAULT 0 CHECK (seconds >= 0),
  words           integer NOT NULL DEFAULT 0 CHECK (words >= 0),
  dedup_key       text,
  UNIQUE (organization_id, user_id, dedup_key)
);
CREATE INDEX learning_activity_owner ON lesen.learning_activity (organization_id, user_id, occurred_at);
CREATE INDEX learning_activity_completed ON lesen.learning_activity (organization_id, occurred_at) WHERE type = 'READING_COMPLETED';

CREATE FUNCTION lesen.learning_activity_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'learning_activity is append-only';
END $$;
CREATE TRIGGER learning_activity_append_only BEFORE UPDATE ON lesen.learning_activity
  FOR EACH ROW EXECUTE FUNCTION lesen.learning_activity_append_only();

-- la proyección por día (en la zona horaria de la persona) y por idioma. Se puede tirar y reconstruir desde
-- learning_activity: es lo que pasa cuando la persona cambia de zona horaria
CREATE TABLE lesen.daily_progress (
  organization_id    uuid NOT NULL,
  user_id            uuid NOT NULL,
  day                date NOT NULL,
  language           text NOT NULL,
  seconds            integer NOT NULL DEFAULT 0,
  readings_started   integer NOT NULL DEFAULT 0,
  readings_completed integer NOT NULL DEFAULT 0,
  words_read         integer NOT NULL DEFAULT 0,
  words_saved        integer NOT NULL DEFAULT 0,
  words_reviewed     integer NOT NULL DEFAULT 0,
  assessments        integer NOT NULL DEFAULT 0,
  PRIMARY KEY (organization_id, user_id, day, language)
);

-- las metas de cada persona. La meta diaria en minutos es la del perfil (learner_profile.daily_minutes); aquí van los
-- días de la semana que la persona quiere leer (los demás son de descanso y nunca rompen la racha), su zona horaria y
-- si quiere ver el resumen semanal al abrir la aplicación
CREATE TABLE lesen.progress_settings (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  weekly_days     integer NOT NULL DEFAULT 3 CHECK (weekly_days BETWEEN 1 AND 7),
  time_zone       text NOT NULL DEFAULT 'UTC',
  weekly_summary  boolean NOT NULL DEFAULT false,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, user_id)
);

-- los logros: una regla de datos (qué se cuenta y desde cuánto). Se conceden una sola vez y nunca se retiran
CREATE TABLE lesen.achievement (
  code      text PRIMARY KEY,
  kind      text NOT NULL CHECK (kind IN ('READINGS_COMPLETED', 'MINUTES', 'WORDS_SAVED', 'WORDS_REVIEWED', 'ASSESSMENTS', 'ACTIVE_DAYS',
                                          'WEEKS_MET', 'BEST_STREAK')),
  threshold integer NOT NULL CHECK (threshold > 0),
  position  integer NOT NULL
);

INSERT INTO lesen.achievement (code, kind, threshold, position) VALUES
  ('FIRST_READING', 'READINGS_COMPLETED', 1, 10),
  ('READINGS_5', 'READINGS_COMPLETED', 5, 20),
  ('READINGS_20', 'READINGS_COMPLETED', 20, 30),
  ('FIRST_HOUR', 'MINUTES', 60, 40),
  ('TEN_HOURS', 'MINUTES', 600, 50),
  ('FIRST_WORD', 'WORDS_SAVED', 1, 60),
  ('WORDS_50', 'WORDS_SAVED', 50, 70),
  ('FIRST_REVIEW', 'WORDS_REVIEWED', 1, 80),
  ('REVIEWS_100', 'WORDS_REVIEWED', 100, 90),
  ('FIRST_ASSESSMENT', 'ASSESSMENTS', 1, 100),
  ('DAYS_10', 'ACTIVE_DAYS', 10, 110),
  ('FIRST_WEEK', 'WEEKS_MET', 1, 120),
  ('STREAK_4', 'BEST_STREAK', 4, 130);

CREATE TABLE lesen.learner_achievement (
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  code            text NOT NULL REFERENCES lesen.achievement (code),
  earned_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, user_id, code)
);

-- listas de lecturas propias («para el viaje», «con mi hija»): solo las ve y cambia su dueña
CREATE TABLE lesen.reading_list (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  user_id         uuid NOT NULL,
  name            text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX reading_list_name ON lesen.reading_list (organization_id, user_id, lower(name));

CREATE TABLE lesen.reading_list_item (
  list_id    uuid NOT NULL REFERENCES lesen.reading_list (id) ON DELETE CASCADE,
  reading_id uuid NOT NULL REFERENCES lesen.reading (id) ON DELETE CASCADE,
  added_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (list_id, reading_id)
);

-- lo ocurrido antes de esta versión, reconstruido desde las tablas que ya lo guardaban, para que el panel no empiece
-- en cero. El tiempo de lectura anterior no se sabe por día: cuenta el día de la última actualización
INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, reading_id, dedup_key)
SELECT p.organization_id, p.user_id, 'READING_STARTED', p.started_at, r.language, p.reading_id, 'started:' || p.reading_id
FROM lesen.reading_progress p JOIN lesen.reading r ON r.id = p.reading_id;

INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, reading_id, seconds)
SELECT p.organization_id, p.user_id, 'PROGRESS', p.updated_at, r.language, p.reading_id, p.seconds_read
FROM lesen.reading_progress p JOIN lesen.reading r ON r.id = p.reading_id
WHERE p.seconds_read > 0;

INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, reading_id, words, dedup_key)
SELECT p.organization_id, p.user_id, 'READING_COMPLETED', p.completed_at, r.language, p.reading_id, v.word_count, 'completed:' || p.reading_id
FROM lesen.reading_progress p
JOIN lesen.reading r ON r.id = p.reading_id
JOIN lesen.reading_version v ON v.reading_id = p.reading_id AND v.number = p.version_number
WHERE p.completed_at IS NOT NULL;

INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, dedup_key)
SELECT organization_id, user_id, 'WORD_SAVED', saved_at, language, 'saved:' || id
FROM lesen.vocabulary_entry;

INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, dedup_key)
SELECT rv.organization_id, rv.user_id, 'WORD_REVIEWED', rv.reviewed_at, x.language, 'review:' || rv.client_id
FROM lesen.vocabulary_review rv JOIN lesen.vocabulary_entry x ON x.id = rv.vocabulary_id;

INSERT INTO lesen.learning_activity (organization_id, user_id, type, occurred_at, language, reading_id, dedup_key)
SELECT a.organization_id, a.user_id, 'ASSESSMENT_SUBMITTED', a.submitted_at, r.language, r.id, 'attempt:' || a.id
FROM lesen.assessment_attempt a
JOIN lesen.assessment s ON s.id = a.assessment_id
JOIN lesen.reading_version v ON v.id = s.version_id
JOIN lesen.reading r ON r.id = v.reading_id;

-- la proyección de lo anterior, en UTC (nadie había elegido zona horaria todavía)
INSERT INTO lesen.daily_progress
  (organization_id, user_id, day, language, seconds, readings_started, readings_completed, words_read, words_saved, words_reviewed, assessments)
SELECT organization_id, user_id, (occurred_at AT TIME ZONE 'UTC')::date, language,
       COALESCE(sum(seconds), 0),
       count(*) FILTER (WHERE type = 'READING_STARTED'),
       count(*) FILTER (WHERE type = 'READING_COMPLETED'),
       COALESCE(sum(words) FILTER (WHERE type = 'READING_COMPLETED'), 0),
       count(*) FILTER (WHERE type = 'WORD_SAVED'),
       count(*) FILTER (WHERE type = 'WORD_REVIEWED'),
       count(*) FILTER (WHERE type = 'ASSESSMENT_SUBMITTED')
FROM lesen.learning_activity
GROUP BY organization_id, user_id, (occurred_at AT TIME ZONE 'UTC')::date, language;
