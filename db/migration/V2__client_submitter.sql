-- Link each connection request to the account that submitted it (Keycloak `sub`),
-- so applicants can follow their own requests and admins cannot review their own.
-- The subject is an opaque id, not personal data in itself.
ALTER TABLE client ADD COLUMN submitted_by text NOT NULL;

CREATE INDEX client_submitted_by_idx ON client (submitted_by, created_at DESC);

COMMENT ON COLUMN client.submitted_by IS 'OIDC subject (Keycloak user id) of the applicant';
