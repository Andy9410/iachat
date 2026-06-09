ALTER TABLE whiteboards
  ADD COLUMN IF NOT EXISTS intent TEXT;

-- intent = intención educativa actual del workspace ("Resolución guiada"),
-- p. ej. "resolver ejercicio 2", "explicar el algoritmo de ordenamiento".
