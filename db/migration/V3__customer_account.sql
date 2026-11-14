-- Links a customer login to the smart meter at their consumption place, so the
-- customer portal can show that meter's consumption and the faults in its area.
-- `username` is the Keycloak username, which is the client id the customer logs
-- in with (Keycloak stores usernames in lower case). No personal data here.
CREATE TABLE customer_account (
    username   text        PRIMARY KEY CHECK (username = lower(username)),
    region     text        NOT NULL REFERENCES region (code),
    meter_id   text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (meter_id, region) REFERENCES meter (id, region)
);
COMMENT ON TABLE customer_account IS 'Customer login (client id) -> meter. Area (county, locality) comes from the meter.';
