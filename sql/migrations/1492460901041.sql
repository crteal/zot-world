CREATE OR REPLACE function array_subtract(array1 anyarray, array2 anyarray)
returns anyarray language sql as $$
    SELECT array_agg(elem)
    FROM unnest(array1) elem
    WHERE elem <> all(array2)
$$;

ALTER TABLE IF EXISTS posts ADD COLUMN likes uuid[];
