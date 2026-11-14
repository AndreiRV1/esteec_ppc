# Documente de racordare – ce conține fiecare

Research notes behind the `client` / `client_document` tables. Sources: the
standard household connection form *Cerere tip de racordare pentru loc de consum
casnic* (DEER-PO-MAD-1.7-F 09) and ANRE Order 59/2013 (*Regulamentul privind
racordarea utilizatorilor la rețelele electrice de interes public*, amended many
times, most recently in 2026). Rețele Electrice uses the same regulation; its own
form may differ in layout, not in substance.

## 1. Cererea de racordare (`CERERE_RACORDARE`)

| Field | Personal data? | Stored in DB? |
|---|---|---|
| Nume și prenume solicitant | yes | no (archive only) |
| Domiciliu (localitate, comună, județ, stradă, nr., bloc, ap., sector) | yes | no |
| Telefon, e-mail | yes | no |
| CI seria + număr | yes | no |
| Tip solicitare: loc de consum nou / spor de putere / separare / actualizare date (certificat de racordare) | no | `client.request_type` |
| Adresa locului de consum (localitate, comună, județ, stradă…) | yes, at street level | only `county`, `locality` |
| Componența locuinței, puteri instalate: iluminat, monofazat, trifazat (kW) | no | not modelled yet |
| Putere instalată totală Pi (kW), putere absorbită Pa (kW), Sa (kVA) | no | `client.requested_power_kw` (= Pa) |
| Opțiune tip tarif | no | not modelled yet |
| Bifă pentru fiecare document anexat, semnătura solicitantului | — | `client_document` rows |

Persoană juridică: denumire, CUI, nr. Registrul Comerțului, reprezentant legal
(instead of CI/CNP). `identity_hmac` is computed from the CNP or the CUI.

## 2. Actul de identitate (`ACT_IDENTITATE`)

A copy of the CI/BI/passport (for a company, the trade-register certificate). Holds
the most sensitive data: nume, CNP, seria/numărul, data nașterii, domiciliu, data
expirării, emitent (SPCLEP). **Nothing from it is stored in clear.** The backend
should check that the document has not expired (validated at upload, not persisted).

## 3. Avizul (`AVIZ_AMPLASAMENT` / `CERTIFICAT_URBANISM`)

The form asks for different permits depending on the case:

- **Certificat de urbanism** (for a new building or an extension), issued by the
  primărie or the county council: number/date, issuer, validity (12–24 months),
  purpose, address of the plot. Must be valid when the request is filed.
- **Aviz de amplasament** (new building or extension), issued by the distribution
  operator's branch: number/date, branch, validity, the conditions set.
- Other documents the form lists as needed: autorizație de construire, plan de
  situație / schiță de amplasament, PUZ/PUD if the certificat de urbanism asks for
  it, act de proprietate / drept de folosință, acord notarial (separare), memoriu
  tehnic, **declarație pe propria răspundere** (data authenticity and copies
  match the originals), and the signed GDPR information note.

`client_document.issuer`, `issued_on` and `valid_until` capture what an admin needs
to check that a permit is still valid, without personal data.

## What the backend has to do on upload

1. Require at least `CERERE_RACORDARE`, `ACT_IDENTITATE` and one permit
   (`AVIZ_AMPLASAMENT` or `CERTIFICAT_URBANISM`).
2. Hash each file (SHA-256) and write one `client_document` row per file.
3. Build the zip, encrypt it (AES-256-GCM, per-file data key wrapped by a master
   key kept outside the DB and the repo), upload it per `storage_policy`, and
   record `sha256` (plain zip) plus `stored_sha256` (ciphertext) in `stored_file`.
4. Insert the `client` row (`status = PENDING`) and an `audit_log` entry, in the same
   transaction as the metadata.
