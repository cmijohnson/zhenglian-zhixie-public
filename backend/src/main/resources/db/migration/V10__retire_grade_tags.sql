-- Grade is a dedicated customer profile field. Keep historical tag rows and
-- customer associations for audit/rollback, but remove them from active use.
UPDATE tags
SET active = false,
    updated_at = now()
WHERE active = true
  AND (
      btrim(category) = '年级'
      OR btrim(name) ~ '^(19|20)[0-9]{2}(级)?$'
      OR btrim(name) ~ '^[0-9]{2}(级)?$'
  );
