-- lecturas a medida (ADR-0016): una lectura privada es de quien la pidió. Sin dueña es del catálogo, como hasta ahora.
-- Las dos columnas van juntas: o las dos o ninguna
ALTER TABLE lesen.reading
  ADD COLUMN owner_organization uuid,
  ADD COLUMN owner_user uuid,
  ADD CONSTRAINT reading_owner_both CHECK ((owner_organization IS NULL) = (owner_user IS NULL));

CREATE INDEX reading_owner ON lesen.reading (owner_organization, owner_user) WHERE owner_user IS NOT NULL;
