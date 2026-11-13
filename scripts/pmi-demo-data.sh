#!/usr/bin/env bash
# Creates fresh synthetic duplicate patients for trying the dashboard's PMI Review view by hand.
# Same scenario as smoke-test section 15, but it does NOT run matching — you do that from the UI.
#
#   A  + A2  same person in two feeds (same name, DOB, phone, address)  → auto-merge (deterministic)
#   A  + B   day/month-swapped DOB, no phone                             → review queue (~0.65)
#   A  + T   opposite-sex twin (Mario), same DOB/phone/address           → review queue (~0.71)
#
# Each run uses a random surname, so it never collides with earlier runs' merges or exclusions.
# Silver rows go in with SQL (no Silver parser exists yet); each patient's provisional master is
# created the real way, through fhir-compliance-service's GoldWriter (POST /api/fhir/build/patients/{id}).
# Usage: ./scripts/pmi-demo-data.sh   (stack up via docker compose)
set -euo pipefail

FHIR_COMPLIANCE_URL="${FHIR_COMPLIANCE_URL:-http://localhost:8084}"
TOKEN_URL="${KEYCLOAK_URL:-http://localhost:8180}/realms/clinicalharmony/protocol/openid-connect/token"
PG_CONTAINER="${PG_CONTAINER:-clinicalharmony-postgres}"

TOKEN="$(curl -s -u clinicalharmony-app:clinicalharmony-app-secret -d grant_type=client_credentials \
  --data-urlencode "scope=clinicalharmony/pipeline.write" "$TOKEN_URL" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))")"   # DEV-ONLY secret
[[ -n "$TOKEN" ]] || { echo "Couldn't get a token from Keycloak ($TOKEN_URL)" >&2; exit 1; }

read -r SFX LAST DOB DOB_SWAP PHONE4 < <(python3 -c "
import random, string
r = random.SystemRandom()
sfx = ''.join(r.choice(string.ascii_lowercase) for _ in range(6))
d = m = 0
while d == m: d, m = r.randint(1, 12), r.randint(1, 12)
y = r.randint(1940, 1999)
print(sfx, 'Demo' + sfx.capitalize(), f'{y}-{m:02d}-{d:02d}', f'{y}-{d:02d}-{m:02d}', r.randint(1000, 9999))")

sql() { docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atqc "$1" | head -1; }
ins() { # source key first gender dob address phone
  sql "INSERT INTO silver.patients (source_system, source_patient_id, mrn, first_name, last_name, date_of_birth, gender, address_line1, city, state, postal_code, phone)
       VALUES ('$1', 'DEMO-$SFX-$2', 'M$2$SFX', '$3', '$LAST', '$5', '$4', '$6', 'Atlanta', 'GA', '30301', $7) RETURNING id"
}
A="$(ins DEMO_EPIC A Maria female "$DOB" '12 Harbor Rd' "'404-555-$PHONE4'")"
A2="$(ins DEMO_CLAIMS A2 Maria female "$DOB" '12 Harbor Road' "'(404) 555-$PHONE4'")"
B="$(ins DEMO_LAB B Maria female "$DOB_SWAP" '88 Peachtree St' NULL)"
T="$(ins DEMO_EPIC T Mario male "$DOB" '12 Harbor Rd' "'404-555-$PHONE4'")"

for id in "$A" "$A2" "$B" "$T"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$FHIR_COMPLIANCE_URL/api/fhir/build/patients/$id")"
  [[ "$code" == 2* ]] || { echo "Building Silver patient $id failed (HTTP $code)" >&2; exit 1; }
done

echo "Created surname $LAST (DOB $DOB) — Silver ids A=$A A2=$A2 B=$B T=$T, each with a PROVISIONAL master:"
docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -c \
  "SELECT p.source_patient_id, p.first_name, p.date_of_birth, x.master_patient_id AS master, m.match_status
     FROM silver.patients p JOIN gold.patient_source_xref x ON x.silver_patient_id = p.id
     JOIN gold.master_patients m ON m.id = x.master_patient_id
    WHERE p.last_name = '$LAST' ORDER BY p.id"
echo "Next: http://localhost:3000/pmi as dana → Run matching (expect 1 auto-merge, 2 queued for review)."
