-- Diccionario bilingüe curado (ADR-0005). Una entrada es un lema de un idioma de estudio; sus acepciones llevan las
-- traducciones a cada idioma de interfaz. grammar es libre por idioma: no se supone género, caso ni aspecto.

CREATE TABLE lesen.dictionary_entry (
  key      text PRIMARY KEY,                  -- de:leuchtturm, de:bank#seat
  language text NOT NULL REFERENCES lesen.language (code),
  lemma    text NOT NULL,
  pos      text NOT NULL,
  ipa      text,
  grammar  jsonb NOT NULL DEFAULT '{}',
  senses   jsonb NOT NULL,
  -- lema normalizado (minúsculas, NFC) para buscar y sugerir
  search   text NOT NULL
);
CREATE INDEX dictionary_entry_search ON lesen.dictionary_entry (language, search text_pattern_ops);

-- forma flexionada → entrada. position ordena las entradas de una forma ambigua: la primera es la de por defecto
CREATE TABLE lesen.dictionary_form (
  language  text NOT NULL,
  form      text NOT NULL,
  entry_key text NOT NULL REFERENCES lesen.dictionary_entry (key) ON DELETE CASCADE,
  position  integer NOT NULL DEFAULT 0,
  PRIMARY KEY (language, form, entry_key)
);

-- búsqueda inversa: desde el idioma de la persona (faro → Leuchtturm)
CREATE TABLE lesen.dictionary_translation (
  entry_key text NOT NULL REFERENCES lesen.dictionary_entry (key) ON DELETE CASCADE,
  language  text NOT NULL,
  term      text NOT NULL,
  PRIMARY KEY (entry_key, language, term)
);
CREATE INDEX dictionary_translation_term ON lesen.dictionary_translation (language, term text_pattern_ops);

-- en qué versiones aparece cada entrada y cuántas veces (para «aparece en estas lecturas»); lo escribe la publicación
CREATE TABLE lesen.reading_entry (
  version_id  uuid NOT NULL REFERENCES lesen.reading_version (id) ON DELETE CASCADE,
  entry_key   text NOT NULL,
  occurrences integer NOT NULL,
  PRIMARY KEY (version_id, entry_key)
);
CREATE INDEX reading_entry_key ON lesen.reading_entry (entry_key);
