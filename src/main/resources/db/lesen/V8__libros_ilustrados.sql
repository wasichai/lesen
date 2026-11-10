-- libros ilustrados (ADR-0014): el formato de cada versión y las ilustraciones como archivos propios

-- el formato es de la versión, como el texto: una lectura podría pasar de texto a libro en una versión nueva
ALTER TABLE lesen.reading_version
  ADD COLUMN format text NOT NULL DEFAULT 'TEXT' CHECK (format IN ('TEXT', 'PICTURE_BOOK'));

CREATE INDEX reading_version_format ON lesen.reading_version (format) WHERE format <> 'TEXT';

-- el formato tampoco cambia en una versión publicada (ADR-0003)
CREATE OR REPLACE FUNCTION lesen.reading_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.content IS DISTINCT FROM OLD.content OR NEW.title IS DISTINCT FROM OLD.title
     OR NEW.subtitle IS DISTINCT FROM OLD.subtitle OR NEW.summary IS DISTINCT FROM OLD.summary
     OR NEW.number IS DISTINCT FROM OLD.number OR NEW.reading_id IS DISTINCT FROM OLD.reading_id
     OR NEW.content_hash IS DISTINCT FROM OLD.content_hash OR NEW.format IS DISTINCT FROM OLD.format THEN
    RAISE EXCEPTION 'una versión publicada de una lectura es inmutable';
  END IF;
  IF OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED' THEN
    RAISE EXCEPTION 'una versión retirada no vuelve a publicarse';
  END IF;
  RETURN NEW;
END $$;

ALTER TABLE lesen.media DROP CONSTRAINT media_kind_check;
ALTER TABLE lesen.media ADD CONSTRAINT media_kind_check CHECK (kind IN ('AUDIO', 'COVER', 'ILLUSTRATION'));
