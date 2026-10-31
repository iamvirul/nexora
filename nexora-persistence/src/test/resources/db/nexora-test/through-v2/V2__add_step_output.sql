-- Step outputs (#32): what each completed step returned, as a JSON object.
--
-- output_json holds at most persistence.stepOutputMaxBytes (64 KB by default) of UTF-8 JSON and
-- is NULL for steps with no output. output_truncated marks rows that keep only a preview of a
-- larger output. Existing rows get NULL and FALSE, since their outputs were never recorded.
--
-- Both columns are nullable or defaulted, so instances still on the previous release keep
-- writing steps during a rolling deploy.

ALTER TABLE nexora_steps ADD COLUMN output_json TEXT;

ALTER TABLE nexora_steps ADD COLUMN output_truncated BOOLEAN DEFAULT FALSE NOT NULL;
