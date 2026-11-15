-- A file whose policy storage was down when it arrived is written to the other file storage instead
-- (FTP <-> S3). This column keeps the storage the policy asked for; NULL when the policy was followed.
ALTER TABLE stored_file
    ADD COLUMN fallback_from text CHECK (fallback_from IN ('DATABASE', 'S3', 'FTP')),
    ADD CONSTRAINT stored_file_fallback_elsewhere CHECK (fallback_from IS NULL OR fallback_from <> backend);
