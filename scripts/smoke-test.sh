#!/usr/bin/env bash
# End-to-end smoke test for ingestion-service, ontology-service, rules-engine-service,
# fhir-compliance-service, patient-index-service, and api-gateway.
# Assumes the stack is already up: `docker-compose up -d` from the repo root.
# Usage: ./scripts/smoke-test.sh
set -uo pipefail

INGESTION_URL="${INGESTION_URL:-http://localhost:8081}"
ONTOLOGY_URL="${ONTOLOGY_URL:-http://localhost:8082}"
RULES_URL="${RULES_URL:-http://localhost:8083}"
FHIR_COMPLIANCE_URL="${FHIR_COMPLIANCE_URL:-http://localhost:8084}"
PMI_URL="${PMI_URL:-http://localhost:8085}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
TOKEN_URL="$KEYCLOAK_URL/realms/clinicalharmony/protocol/openid-connect/token"
CLIENT_ID="${CLIENT_ID:-clinicalharmony-app}"
CLIENT_SECRET="${CLIENT_SECRET:-clinicalharmony-app-secret}"  # dev-only secret from keycloak/realm-clinicalharmony.json

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/fixtures"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

# Bearer token sent on every fhir-compliance-service call (check_http/check_header/fc_curl). Most
# of fhir-compliance-service needs one since Phase 5b slice 4; public endpoints just ignore it.
# Section 14 swaps it out to test the 401/403 paths.
TOKEN=""
fc_curl() {
  if [[ -n "$TOKEN" ]]; then curl -H "Authorization: Bearer $TOKEN" "$@"; else curl "$@"; fi
}

# get_token "SPACE SEPARATED SCOPES" — SMART backend-services client_credentials grant against
# Keycloak; prints the access token (empty on failure). Omit the scope arg to request none.
get_token() {
  local scope_args=()
  [[ $# -gt 0 ]] && scope_args=(--data-urlencode "scope=$1")
  curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials "${scope_args[@]+"${scope_args[@]}"}" "$TOKEN_URL" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
}

# check_http NAME METHOD URL EXPECTED_CODE [CONTENT_TYPE] [BODY_FILE_OR_STRING]
check_http() {
  local name="$1" method="$2" url="$3" expected="$4" content_type="${5:-}" body="${6:-}"
  local args=(-s -o "$TMP/last_response.json" -w "%{http_code}" -X "$method" "$url")
  [[ -n "$content_type" ]] && args+=(-H "Content-Type: $content_type")
  if [[ -n "$body" && -f "$body" ]]; then
    args+=(--data-binary "@$body")
  elif [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  local actual
  actual="$(fc_curl "${args[@]}")"
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS  $name (HTTP $actual)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $name (expected HTTP $expected, got $actual)"
    cat "$TMP/last_response.json"
    FAIL=$((FAIL + 1))
  fi
}

# check_json NAME JSON_FILE PYTHON_EXPR_ON_d EXPECTED_REPR
check_json() {
  local name="$1" json_file="$2" expr="$3" expected="$4"
  local actual
  actual="$(python3 -c "import json; d=json.load(open('$json_file')); print($expr)" 2>&1)"
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS  $name ($actual)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $name (expected $expected, got $actual)"
    FAIL=$((FAIL + 1))
  fi
}

section() { echo; echo "=== $1 ==="; }

# check_sql NAME SQL EXPECTED — runs against the compose Postgres container (Gold-layer checks
# that no HTTP endpoint exposes yet, e.g. data_lineage, until the Phase 7 api-gateway).
PG_CONTAINER="${PG_CONTAINER:-clinicalharmony-postgres}"
check_sql() {
  local name="$1" sql="$2" expected="$3"
  local actual
  actual="$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "$sql" 2>&1)"
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS  $name ($actual)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $name (expected $expected, got $actual)"
    FAIL=$((FAIL + 1))
  fi
}

# check_header NAME URL HEADER EXPECTED — GET, compare one response header (case-insensitive name).
check_header() {
  local name="$1" url="$2" header="$3" expected="$4"
  local actual
  actual="$(fc_curl -s -o /dev/null -D - "$url" | tr -d '\r' | awk -F': ' -v h="$(echo "$header" | tr '[:upper:]' '[:lower:]')" 'tolower($1)==h{print $2}')"
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS  $name ($actual)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $name (expected '$expected', got '$actual')"
    FAIL=$((FAIL + 1))
  fi
}

section "0. Health checks"
for pair in "ingestion:$INGESTION_URL" "ontology:$ONTOLOGY_URL" "rules-engine:$RULES_URL" "fhir-compliance:$FHIR_COMPLIANCE_URL" "patient-index:$PMI_URL"; do
  name="${pair%%:*}"; url="${pair#*:}"
  check_http "$name-service is up" GET "$url/actuator/health" 200
done

check_http "keycloak realm clinicalharmony is up" GET "$KEYCLOAK_URL/realms/clinicalharmony/.well-known/openid-configuration" 200

# Full-access token for sections 7–13: every SMART read scope + the pipeline.write operational scope.
TOKEN="$(get_token "system/*.rs clinicalharmony/pipeline.write")"
if [[ -n "$TOKEN" ]]; then
  echo "  PASS  client_credentials token issued for $CLIENT_ID"; PASS=$((PASS + 1))
else
  echo "  FAIL  could not get a token from $TOKEN_URL (is the keycloak container healthy?)"; FAIL=$((FAIL + 1))
fi

section "1. ingestion-service — HL7 v2 (REST)"
check_http "valid ADT^A01 accepted" POST "$INGESTION_URL/api/ingestion/hl7" 201 "text/plain" "$FIXTURES/hl7_valid.txt"
check_http "malformed HL7 quarantined" POST "$INGESTION_URL/api/ingestion/hl7" 422 "text/plain" "$FIXTURES/hl7_malformed.txt"

section "2. ingestion-service — FHIR JSON"
check_http "valid Patient resource accepted" POST "$INGESTION_URL/api/ingestion/fhir" 201 "application/json" "$FIXTURES/fhir_valid_patient.json"
check_http "resource with no resourceType quarantined" POST "$INGESTION_URL/api/ingestion/fhir" 422 "application/json" "$FIXTURES/fhir_malformed.json"

section "3. ingestion-service — Claims CSV"
check_http "valid 2-row claims file accepted" POST "$INGESTION_URL/api/ingestion/claims" 201 "text/csv" "$FIXTURES/claims_valid.csv"
check_http "ragged claims file quarantined whole" POST "$INGESTION_URL/api/ingestion/claims" 422 "text/csv" "$FIXTURES/claims_ragged.csv"

section "4. ontology-service — code validation + lookups"
curl -s -X POST "$ONTOLOGY_URL/api/ontology/validate" -H "Content-Type: application/json" \
  --data-binary "@$FIXTURES/ontology_validate_request.json" > "$TMP/ontology_validate.json"
check_json "known ICD-10 code E11.9 is valid" "$TMP/ontology_validate.json" \
  "d['conditionResults'][0]['outcome']['valid']" "True"
check_json "unknown ICD-10 code Z99.999 is invalid" "$TMP/ontology_validate.json" \
  "d['conditionResults'][1]['outcome']['valid']" "False"
check_json "overall report correctly marked not all-valid" "$TMP/ontology_validate.json" \
  "d['allValid']" "False"
check_http "known ICD-10 single lookup (200)" GET "$ONTOLOGY_URL/api/ontology/codes/icd10/E11.9" 200
check_http "unknown ICD-10 single lookup (404)" GET "$ONTOLOGY_URL/api/ontology/codes/icd10/Q00.00" 404
curl -s "$ONTOLOGY_URL/api/ontology/map?source=SNOMED&code=44054006&target=ICD10" > "$TMP/mapping.json"
check_json "SNOMED 44054006 maps to ICD-10 E11.9" "$TMP/mapping.json" "d[0]['targetCode']" "E11.9"

section "5. rules-engine-service — full rule evaluation"
curl -s -X POST "$RULES_URL/api/rules/evaluate" -H "Content-Type: application/json" \
  --data-binary "@$FIXTURES/rules_evaluate_request.json" > "$TMP/rules_evaluate.json"
check_json "ontology validation ran alongside rules" "$TMP/rules_evaluate.json" \
  "d['ontologyValidationAvailable']" "True"
# DIAGNOSIS_DATE_SANITY runs once per condition (2 conditions here), so 8 results
# across 7 distinct rule codes is expected, not a flat count of 7.
check_json "all 7 distinct built-in rules were evaluated" "$TMP/rules_evaluate.json" \
  "len(set(r['ruleCode'] for r in d['ruleResults']))" "7"
check_json "GENDER_PREGNANCY_CONFLICT fired" "$TMP/rules_evaluate.json" \
  "[r for r in d['ruleResults'] if r['ruleCode']=='GENDER_PREGNANCY_CONFLICT'][0]['passed']" "False"
check_json "MEDICATION_ALLERGY_CONFLICT fired" "$TMP/rules_evaluate.json" \
  "[r for r in d['ruleResults'] if r['ruleCode']=='MEDICATION_ALLERGY_CONFLICT'][0]['passed']" "False"
check_json "LAB_REFERENCE_RANGE fired" "$TMP/rules_evaluate.json" \
  "[r for r in d['ruleResults'] if r['ruleCode']=='LAB_REFERENCE_RANGE'][0]['passed']" "False"

section "6. rules-engine-service — rule catalog + live toggle"
check_http "GET /api/rules (active rule list)" GET "$RULES_URL/api/rules" 200
check_http "GET single known rule" GET "$RULES_URL/api/rules/PATIENT_AGE_CONSISTENCY" 200
check_http "GET unknown rule returns 404" GET "$RULES_URL/api/rules/NOT_A_REAL_RULE" 404

# Toggle off, confirm it stops firing, then always restore it — even on failure.
curl -s -X PUT "$RULES_URL/api/rules/PATIENT_AGE_CONSISTENCY/toggle" > /dev/null
curl -s -X POST "$RULES_URL/api/rules/evaluate" -H "Content-Type: application/json" \
  -d '{"patientId": 999, "dateOfBirth": "1980-01-01", "statedAge": 25}' > "$TMP/toggled_off.json"
check_json "toggled-off rule is absent from evaluation" "$TMP/toggled_off.json" \
  "'PATIENT_AGE_CONSISTENCY' in [r['ruleCode'] for r in d['ruleResults']]" "False"
curl -s -X PUT "$RULES_URL/api/rules/PATIENT_AGE_CONSISTENCY/toggle" > "$TMP/restored.json"
check_json "rule restored to active" "$TMP/restored.json" "d['active']" "True"

section "7. fhir-compliance-service — US Core structural validation"
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/validate" -H "Content-Type: application/json" \
  --data-binary "@$FIXTURES/fhir_compliance_validate_request.json" > "$TMP/fhir_validate.json"
check_json "complete Patient (p1) is valid" "$TMP/fhir_validate.json" \
  "[r for r in d['resourceResults'] if r['resourceId']=='p1'][0]['valid']" "True"
check_json "incomplete Patient (p2) is invalid" "$TMP/fhir_validate.json" \
  "[r for r in d['resourceResults'] if r['resourceId']=='p2'][0]['valid']" "False"
check_json "MedicationRequest satisfying medication[x] anyOf is valid (m1)" "$TMP/fhir_validate.json" \
  "[r for r in d['resourceResults'] if r['resourceId']=='m1'][0]['valid']" "True"
check_json "MedicationRequest missing medication[x] is invalid (m2)" "$TMP/fhir_validate.json" \
  "[r for r in d['resourceResults'] if r['resourceId']=='m2'][0]['valid']" "False"

section "8. fhir-compliance-service — FHIR generation + Gold persistence + rule catalog"
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/generate" -H "Content-Type: application/json" \
  --data-binary "@$FIXTURES/fhir_compliance_generate_request.json" > "$TMP/fhir_generate.json"
check_json "generated Bundle has all 7 resource types" "$TMP/fhir_generate.json" \
  "len(set(e['resource']['resourceType'] for e in d['entry']))" "7"

# Round-trip the generated resources back through /validate — a live cross-check that the
# generator and validator agree, not just an assumption that generated output is compliant.
python3 -c "
import json
d = json.load(open('$TMP/fhir_generate.json'))
resources = [e['resource'] for e in d['entry']]
json.dump({'patientId': 901, 'fhirResources': resources}, open('$TMP/fhir_roundtrip_request.json', 'w'))
"
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/validate" -H "Content-Type: application/json" \
  --data-binary "@$TMP/fhir_roundtrip_request.json" > "$TMP/fhir_roundtrip.json"
check_json "every generated resource round-trips as US Core valid" "$TMP/fhir_roundtrip.json" \
  "d['allValid']" "True"
check_http "GET /api/fhir-compliance/rules (7 active USCORE rules)" GET "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules" 200
check_json "exactly 7 active USCORE rules" "$TMP/last_response.json" "len(d)" "7"
check_http "GET unknown rule returns 404" GET "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/NOT_A_REAL_RULE" 404

# Toggle off, confirm it's skipped (not failed) from validation, then always restore it.
fc_curl -s -X PUT "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle" > /dev/null
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/validate" -H "Content-Type: application/json" \
  -d '{"patientId": 903, "fhirResources": [{"resourceType": "Patient", "id": "p-incomplete"}]}' > "$TMP/fhir_toggled_off.json"
check_json "toggled-off Patient rule is skipped, not failed" "$TMP/fhir_toggled_off.json" \
  "d['allValid']" "True"
fc_curl -s -X PUT "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle" > "$TMP/fhir_restored.json"
check_json "USCORE_PATIENT_COMPLETENESS restored to active" "$TMP/fhir_restored.json" "d['active']" "True"

section "9. fhir-compliance-service — HL7 validator vs US Core 6.1.0 (Phase 5b)"
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","name":[{"family":""}]}' > "$TMP/hl7_validate_bad.json"
check_json "bare Patient is checked against US Core and fails" "$TMP/hl7_validate_bad.json" "d['compliant']" "False"
check_json "missing gender reported at its FHIRPath with a suggested fix" "$TMP/hl7_validate_bad.json" \
  "[v['suggestedFix'] is not None for v in d['violations'] if v['location']=='Patient.gender']" "[True]"
check_json "empty string reported at Patient.name[0].family" "$TMP/hl7_validate_bad.json" \
  "any(v['location']=='Patient.name[0].family' and v['severity']=='ERROR' for v in d['violations'])" "True"
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Patient","identifier":[{"system":"urn:x","value":"1"}],"name":[{"family":"Doe"}],"gender":"female"}' \
  > "$TMP/hl7_validate_good.json"
check_json "complete Patient is US Core compliant" "$TMP/hl7_validate_good.json" "d['compliant']" "True"
check_http "non-FHIR payload rejected (400)" POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" 400 "application/json" '{"foo":1}'

section "10. fhir-compliance-service — Silver → US Core FHIR → Gold (Phase 5b)"
# Needs docs/db-init/16 + 17 applied (compliance_notes column + seed Silver patients).
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/build" > "$TMP/silver_build.json"
check_json "seed patients SEED-0001/0002 build fully compliant" "$TMP/silver_build.json" \
  "all(r['compliantCount']==r['resourceCount'] for r in d if r['resources'][0]['fhirId'] in ('silver-patient-1','silver-patient-2'))" "True"
check_json "deliberately bad seed patient is flagged (blank name + blank code)" "$TMP/silver_build.json" \
  "sorted(e['location'] for r in d for x in r['resources'] if not x['usCoreCompliant'] for e in x['errors'])" \
  "['Condition.code', 'Patient.name']"
check_http "build for unknown Silver patient returns 404" POST "$FHIR_COMPLIANCE_URL/api/fhir/build/patients/999999" 404

section "11. fhir-compliance-service — C-CDA → FHIR R4 Bundle (Phase 5b slice 2)"
CCDA_URL="$FHIR_COMPLIANCE_URL/api/fhir/convert/ccda"
check_http "complete CCD converts (200)" POST "$CCDA_URL" 200 "application/xml" "$FIXTURES/ccda_compliant.xml"
cp "$TMP/last_response.json" "$TMP/ccda_good.json"
check_json "every resource from the complete CCD is US Core compliant" "$TMP/ccda_good.json" "d['allCompliant']" "True"
check_json "Patient + 3 Conditions + 2 MedicationRequests + 3 Observations" "$TMP/ccda_good.json" \
  "sorted((t, [r['resourceType'] for r in d['resources']].count(t)) for t in {r['resourceType'] for r in d['resources']})" \
  "[('Condition', 3), ('MedicationRequest', 2), ('Observation', 3), ('Patient', 1)]"
check_json "Bundle is a collection and every subject resolves to the Patient entry" "$TMP/ccda_good.json" \
  "(d['bundle']['type'], {e['resource']['subject']['reference'] for e in d['bundle']['entry'][1:]} == {d['bundle']['entry'][0]['fullUrl']})" \
  "('collection', True)"
fc_curl -s -X POST "$CCDA_URL" -H "Content-Type: application/xml" --data-binary "@$FIXTURES/ccda_compliant.xml" > "$TMP/ccda_good_again.json"
python3 -c "import json; print(json.dumps([r['resourceId'] for r in json.load(open('$TMP/ccda_good.json'))['resources']]))" > "$TMP/ccda_ids_first.json"
check_json "re-converting the same document yields the same resource ids" "$TMP/ccda_good_again.json" \
  "[r['resourceId'] for r in d['resources']] == json.load(open('$TMP/ccda_ids_first.json'))" "True"

check_http "incomplete CCD still converts (200 — non-compliance is reported, not rejected)" POST "$CCDA_URL" 200 \
  "text/xml" "$FIXTURES/ccda_noncompliant.xml"
cp "$TMP/last_response.json" "$TMP/ccda_bad.json"
check_json "3 of 7 resources compliant" "$TMP/ccda_bad.json" "(d['compliantCount'], d['resourceCount'])" "(3, 7)"
check_json "each deliberate gap reported at its FHIRPath" "$TMP/ccda_bad.json" \
  "sorted(v['location'] for r in d['resources'] for v in r['validation']['violations'] if v['severity']=='ERROR')" \
  "['Condition.code', 'MedicationRequest.status', 'Observation.status', 'Patient.gender']"

check_http "malformed XML rejected (400)" POST "$CCDA_URL" 400 "application/xml" "$FIXTURES/ccda_malformed.xml"
check_json "400 body explains it's not well-formed" "$TMP/last_response.json" "d['message'].startswith('Not well-formed XML')" "True"
check_http "DOCTYPE / XXE payload rejected (400)" POST "$CCDA_URL" 400 "application/xml" \
  '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><ClinicalDocument xmlns="urn:hl7-org:v3">&e;</ClinicalDocument>'
check_http "XML that isn't a ClinicalDocument rejected (400)" POST "$CCDA_URL" 400 "application/xml" '<Patient xmlns="http://hl7.org/fhir"/>'
check_http "JSON content type rejected (415)" POST "$CCDA_URL" 415 "application/json" '{"resourceType":"Patient"}'
echoed="$(fc_curl -s -o /dev/null -D - -X POST "$CCDA_URL" -H "Content-Type: application/xml" -H "X-Correlation-Id: smoke-ccda-1" \
  --data-binary "@$FIXTURES/ccda_compliant.xml" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
if [[ "$echoed" == "smoke-ccda-1" ]]; then
  echo "  PASS  caller's X-Correlation-Id echoed back ($echoed)"; PASS=$((PASS + 1))
else
  echo "  FAIL  caller's X-Correlation-Id echoed back (got '$echoed')"; FAIL=$((FAIL + 1))
fi

section "12. fhir-compliance-service — GoldWriter: provisional masters + lineage (Phase 5b slice 3)"
# Needs docs/db-init/18 (match_status, nullable lineage source_id, unique lineage/xref indexes).
# Since Phase 6 the PMI (section 15) merges masters, so "one master per Silver patient" is only
# guaranteed for masters it hasn't decided yet (PROVISIONAL).
check_json "every built Silver patient is linked to a master" "$TMP/silver_build.json" \
  "all(r['masterPatientId'] for r in d)" "True"
fc_curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/build" > "$TMP/silver_rebuild.json"
python3 -c "import json; print(json.dumps({str(r['silverPatientId']): r['masterPatientId'] for r in json.load(open('$TMP/silver_build.json'))}))" > "$TMP/masters_first.json"
check_json "rebuilding reuses the same masters (no duplicates)" "$TMP/silver_rebuild.json" \
  "{str(r['silverPatientId']): r['masterPatientId'] for r in d} == json.load(open('$TMP/masters_first.json'))" "True"
check_sql "every Silver patient has exactly one master; PROVISIONAL masters are still 1:1" \
  "SELECT (SELECT count(DISTINCT silver_patient_id) FROM gold.patient_source_xref) = (SELECT count(*) FROM silver.patients) AND (SELECT coalesce(bool_and(source_patient_count = 1 AND match_confidence_score IS NULL), true) FROM gold.master_patients WHERE match_status = 'PROVISIONAL')" "t"
check_sql "every Silver-built Gold resource has exactly one FHIR_MAP lineage row" \
  "SELECT (SELECT count(*) FROM gold.fhir_resources WHERE fhir_id LIKE 'silver-%') = (SELECT count(*) FROM gold.data_lineage WHERE transformation_step = 'FHIR_MAP' AND target_table = 'fhir_resources')" "t"
check_sql "Silver-built lineage chains run SILVER → GOLD (seed rows have no Bronze link)" \
  "SELECT DISTINCT (SELECT string_agg(s->>'stage', '>' ORDER BY o) FROM jsonb_array_elements(transformation_details->'steps') WITH ORDINALITY AS t(s, o)) FROM gold.data_lineage WHERE transformation_step = 'FHIR_MAP'" "SILVER>GOLD"
check_sql "GOLD steps carry 64-hex input and output hashes" \
  "SELECT bool_and(transformation_details->'steps'->-1->>'input_hash' ~ '^[0-9a-f]{64}$' AND transformation_details->'steps'->-1->>'output_hash' ~ '^[0-9a-f]{64}$') FROM gold.data_lineage WHERE transformation_step = 'FHIR_MAP'" "t"
check_sql "Phase 5 /generate resources (section 8) also go through GoldWriter: API → GOLD lineage" \
  "SELECT DISTINCT source_schema || ':' || (SELECT string_agg(s->>'stage', '>' ORDER BY o) FROM jsonb_array_elements(transformation_details->'steps') WITH ORDINALITY AS t(s, o)) FROM gold.data_lineage WHERE transformation_step = 'API_GENERATE'" "api:API>GOLD"
check_sql "Silver-built Gold resources are linked to their patient's master" \
  "SELECT count(*) FROM gold.fhir_resources WHERE fhir_id LIKE 'silver-%' AND master_patient_id IS NULL" "0"
check_sql "seeded CMS-0057 Coverage back-linked to SEED-0001's master" \
  "SELECT f.master_patient_id = x.master_patient_id FROM gold.fhir_resources f, silver.patients p JOIN gold.patient_source_xref x ON x.silver_patient_id = p.id WHERE f.fhir_id = 'cms-coverage-seed-0001' AND p.source_patient_id = 'SEED-0001'" "t"

section "13. fhir-compliance-service — CMS-0057-F payer read API from Gold (Phase 5b slice 3)"
# Needs docs/db-init/19 (synthetic CMS-0057 seed). GoldWriter validates it on startup.
R4="$FHIR_COMPLIANCE_URL/fhir/r4"
SEED1="silver-patient-$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT id FROM silver.patients WHERE source_patient_id = 'SEED-0001'")"
SEED2="silver-patient-$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT id FROM silver.patients WHERE source_patient_id = 'SEED-0002'")"
check_http "revalidate pending Gold rows (200)" POST "$FHIR_COMPLIANCE_URL/api/fhir/gold/revalidate" 200
check_json "nothing left pending after startup validation" "$TMP/last_response.json" "len(d)" "0"
check_sql "12 of 13 seeded resources compliant; the deliberately bad PractitionerRole is not" \
  "SELECT string_agg(fhir_id, ',') FILTER (WHERE NOT is_us_core_compliant) || ' / ' || count(*) FILTER (WHERE is_us_core_compliant) FROM gold.fhir_resources WHERE fhir_id LIKE 'cms-%'" "cms-practitionerrole-2 / 12"
check_sql "every seeded resource has a SEED_LOAD lineage row with no source id" \
  "SELECT count(*) FROM gold.data_lineage WHERE transformation_step = 'SEED_LOAD' AND source_schema = 'seed' AND source_id IS NULL" "13"

check_http "Coverage search by patient" GET "$R4/Coverage?patient=$SEED1" 200
check_json "  → searchset with SEED-0001's Coverage" "$TMP/last_response.json" \
  "(d['resourceType'], d['type'], d['total'], d['entry'][0]['resource']['id'])" "('Bundle', 'searchset', 1, 'cms-coverage-seed-0001')"
check_http "Coverage search by payor (array reference)" GET "$R4/Coverage?payor=Organization/cms-org-payer" 200
check_json "  → both seed Coverages" "$TMP/last_response.json" "d['total']" "2"
check_http "PractitionerRole search by organization" GET "$R4/PractitionerRole?organization=cms-org-provider" 200
check_json "  → 2 roles" "$TMP/last_response.json" "d['total']" "2"
check_http "PractitionerRole search active AND specialty" GET "$R4/PractitionerRole?active=true&specialty=207Q00000X" 200
check_json "  → only the family-medicine role" "$TMP/last_response.json" "[e['resource']['id'] for e in d['entry']]" "['cms-practitionerrole-1']"
check_http "MedicationKnowledge search by RxNorm system|code" GET "$R4/MedicationKnowledge?code=http://www.nlm.nih.gov/research/umls/rxnorm|861007" 200
check_json "  → metformin 500 mg tablet" "$TMP/last_response.json" "d['entry'][0]['resource']['code']['coding'][0]['display']" "metformin hydrochloride 500 MG Oral Tablet"
check_http "MedicationKnowledge search status=active with _count=2" GET "$R4/MedicationKnowledge?status=active&_count=2" 200
check_json "  → total 3, page of 2" "$TMP/last_response.json" "(d['total'], len(d['entry']))" "(3, 2)"
check_http "MeasureReport search by unversioned measure canonical" GET "$R4/MeasureReport?measure=http://ecqi.healthit.gov/ecqms/Measure/DiabetesHemoglobinA1cHbA1cPoorControl9FHIR" 200
check_json "  → matches the version-pinned report" "$TMP/last_response.json" "(d['total'], d['entry'][0]['resource']['subject']['reference'])" "(1, 'Patient/$SEED1')"
check_http "Device search by patient + SNOMED type" GET "$R4/Device?patient=$SEED2&type=http://snomed.info/sct|14106009" 200
check_json "  → SEED-0002's pacemaker" "$TMP/last_response.json" "d['entry'][0]['resource']['id']" "cms-device-seed-0002"

check_http "read Coverage by id" GET "$R4/Coverage/cms-coverage-seed-0001" 200
check_json "  → the stored resource" "$TMP/last_response.json" "(d['resourceType'], d['id'])" "('Coverage', 'cms-coverage-seed-0001')"
check_header "  → served as application/fhir+json" "$R4/Coverage/cms-coverage-seed-0001" "Content-Type" "application/fhir+json;charset=UTF-8"
check_header "  → X-Profile-Compliant true" "$R4/Coverage/cms-coverage-seed-0001" "X-Profile-Compliant" "true"
check_header "MedicationKnowledge validated against Da Vinci Formulary" "$R4/MedicationKnowledge/cms-formulary-617310" \
  "X-Validated-Profile" "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug"
check_header "MeasureReport validated against DEQM and compliant" "$R4/MeasureReport/cms-measurereport-seed-0001" "X-Profile-Compliant" "true"
check_header "Device validated against US Core implantable device" "$R4/Device/cms-device-seed-0002" \
  "X-Validated-Profile" "http://hl7.org/fhir/us/core/StructureDefinition/us-core-implantable-device"
check_header "non-compliant PractitionerRole is served but flagged" "$R4/PractitionerRole/cms-practitionerrole-2" "X-Profile-Compliant" "false"

check_http "unknown id → 404" GET "$R4/Device/does-not-exist" 404
check_json "  → OperationOutcome not-found" "$TMP/last_response.json" "(d['resourceType'], d['issue'][0]['code'])" "('OperationOutcome', 'not-found')"
check_http "unsupported resource type → 404" GET "$R4/Claim/123" 404
check_json "  → OperationOutcome not-supported" "$TMP/last_response.json" "d['issue'][0]['code']" "not-supported"
check_http "unknown search parameter → 400" GET "$R4/Coverage?colour=red" 400
check_json "  → OperationOutcome invalid, names the parameter" "$TMP/last_response.json" \
  "(d['issue'][0]['code'], \"'colour'\" in d['issue'][0]['diagnostics'])" "('invalid', True)"
check_http "search modifier → 400" GET "$R4/Coverage?status:not=active" 400
check_http "_count over the cap → 400" GET "$R4/Device?_count=1000" 400
check_http "wrongly typed reference → 400" GET "$R4/Coverage?patient=Practitioner/cms-practitioner-1" 400
echoed="$(fc_curl -s -o /dev/null -D - -H "X-Correlation-Id: smoke-cms-1" "$R4/Device/cms-device-seed-0002" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
if [[ "$echoed" == "smoke-cms-1" ]]; then
  echo "  PASS  caller's X-Correlation-Id echoed on /fhir/r4 ($echoed)"; PASS=$((PASS + 1))
else
  echo "  FAIL  caller's X-Correlation-Id echoed on /fhir/r4 (got '$echoed')"; FAIL=$((FAIL + 1))
fi

section "14. fhir-compliance-service — SMART on FHIR backend services auth (Phase 5b slice 4)"
FULL_TOKEN="$TOKEN"
check_http "SMART discovery is public (/.well-known)" GET "$FHIR_COMPLIANCE_URL/.well-known/smart-configuration" 200
check_json "  → token_endpoint is the Keycloak realm's" "$TMP/last_response.json" \
  "d['token_endpoint']" "$TOKEN_URL"
check_json "  → client_credentials grant, SMART v2 permissions, Coverage scope advertised" "$TMP/last_response.json" \
  "(d['grant_types_supported'], 'permission-v2' in d['capabilities'], 'system/Coverage.rs' in d['scopes_supported'])" \
  "(['client_credentials'], True, True)"
check_http "SMART discovery also at the FHIR base (spec location)" GET "$R4/.well-known/smart-configuration" 200

TOKEN=""
check_http "no token on /fhir/r4 → 401" GET "$R4/Coverage/cms-coverage-seed-0001" 401
check_json "  → OperationOutcome, issue code login" "$TMP/last_response.json" \
  "(d['resourceType'], d['issue'][0]['code'])" "('OperationOutcome', 'login')"
check_header "  → WWW-Authenticate Bearer challenge" "$R4/Coverage/cms-coverage-seed-0001" "WWW-Authenticate" 'Bearer realm="clinicalharmony"'
echoed="$(curl -s -o /dev/null -D - -H "X-Correlation-Id: smoke-auth-1" "$R4/Coverage/cms-coverage-seed-0001" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
if [[ "$echoed" == "smoke-auth-1" ]]; then
  echo "  PASS  X-Correlation-Id echoed on a 401 ($echoed)"; PASS=$((PASS + 1))
else
  echo "  FAIL  X-Correlation-Id echoed on a 401 (got '$echoed')"; FAIL=$((FAIL + 1))
fi
check_http "no token on POST /api/fhir/build → 401" POST "$FHIR_COMPLIANCE_URL/api/fhir/build" 401
check_json "  → plain JSON error (not an OperationOutcome) off /fhir" "$TMP/last_response.json" "d['error']" "Unauthorized"
check_http "no token on C-CDA convert → 401" POST "$CCDA_URL" 401 "application/xml" "$FIXTURES/ccda_compliant.xml"
check_http "no token on POST /api/fhir/validate → still public (200)" POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" 200 \
  "application/fhir+json" "$FIXTURES/fhir_valid_patient.json"
check_http "no token on GET /api/fhir-compliance/rules → still public (200)" GET "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules" 200

# Flip one character in the middle of the signature (the last base64url char is partly padding bits,
# so changing it may not change the decoded signature at all).
TOKEN="$(python3 -c "import sys; h,p,s=sys.argv[1].split('.'); i=len(s)//2; s=s[:i]+('A' if s[i]!='A' else 'B')+s[i+1:]; print('.'.join([h,p,s]))" "$FULL_TOKEN")"
check_http "tampered token signature → 401" GET "$R4/Coverage/cms-coverage-seed-0001" 401
challenge="$(fc_curl -s -o /dev/null -D - "$R4/Coverage/cms-coverage-seed-0001" | tr -d '\r' | awk -F': ' 'tolower($1)=="www-authenticate"{print $2}')"
if [[ "$challenge" == *'error="invalid_token"'* ]]; then
  echo "  PASS    → WWW-Authenticate says invalid_token"; PASS=$((PASS + 1))
else
  echo "  FAIL    → WWW-Authenticate says invalid_token (got '$challenge')"; FAIL=$((FAIL + 1))
fi

TOKEN="$(get_token)"
check_http "token with no scopes requested → 403 (SMART clients must ask for scopes)" GET "$R4/Coverage/cms-coverage-seed-0001" 403

# The realm offers per-type .rs scopes (read + search); the finer .r-vs-.s split is enforced
# server-side too but only covered by the Java tests, since Keycloak won't issue scopes it doesn't define.
TOKEN="$(get_token "system/Coverage.rs")"
check_http "system/Coverage.rs token reads Coverage by id → 200" GET "$R4/Coverage/cms-coverage-seed-0001" 200
check_http "system/Coverage.rs token searches Coverage → 200" GET "$R4/Coverage?status=active" 200
check_http "system/Coverage.rs token searching MeasureReport → 403" GET "$R4/MeasureReport?status=complete" 403
check_json "  → OperationOutcome forbidden, names system/MeasureReport.s" "$TMP/last_response.json" \
  "(d['issue'][0]['code'], 'system/MeasureReport.s' in d['issue'][0]['diagnostics'])" "('forbidden', True)"
check_header "  → WWW-Authenticate insufficient_scope" "$R4/MeasureReport?status=complete" "WWW-Authenticate" \
  'Bearer realm="clinicalharmony", error="insufficient_scope", scope="system/MeasureReport.s"'
check_http "system/Coverage.rs token reading Device by id → 403" GET "$R4/Device/cms-device-seed-0002" 403

TOKEN="$(get_token "system/Device.rs")"
check_http "system/Device.rs token searches Device → 200" GET "$R4/Device?patient=$SEED2" 200
check_http "read-only token on POST /api/fhir/gold/revalidate → 403 (needs pipeline.write)" POST "$FHIR_COMPLIANCE_URL/api/fhir/gold/revalidate" 403
check_json "  → names clinicalharmony/pipeline.write" "$TMP/last_response.json" \
  "'clinicalharmony/pipeline.write' in d['message']" "True"
check_http "read-only token on PUT rule toggle → 403" PUT "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle" 403
check_http "unsupported type with a valid token still 404s (not 403)" GET "$R4/Claim/123" 404
TOKEN="$FULL_TOKEN"

section "15. patient-index-service — PMI matching, review, merge/unmerge (Phase 6)"
# Needs docs/db-init/20 (pmi schema, merge history, PMI_MATCH rule) + 21 (seed duplicates).
PMI="$PMI_URL/api/pmi"

check_http "matching run (pipeline.write) → 200" POST "$PMI/match/run" 200
check_json "  → run COMPLETED" "$TMP/last_response.json" "d['run']['status']" "COMPLETED"
check_sql "seed: claims-feed Maria Garcia (PMI-0001) merged into SEED-0001's master (deterministic, 1.0)" \
  "SELECT m.match_status || ' ' || g.match_method || ' ' || g.match_confidence_score FROM gold.master_patient_merges g JOIN gold.master_patients m ON m.id = g.survivor_master_id WHERE g.unmerged_at IS NULL AND (SELECT id FROM silver.patients WHERE source_patient_id = 'PMI-0001') = ANY (g.moved_silver_patient_ids) AND g.survivor_master_id = (SELECT x.master_patient_id FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id WHERE p.source_patient_id = 'SEED-0001')" "MATCHED DETERMINISTIC 1.0000"
check_sql "seed: 'Jim' Wilson + James 'Willson' (same MRN) share SEED-0002's master" \
  "SELECT count(DISTINCT x.master_patient_id) FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id WHERE p.source_patient_id IN ('SEED-0002', 'PMI-0002', 'PMI-0006')" "1"
check_sql "seed: opposite-sex twin Mario (PMI-0004) is NOT merged into Maria's master" \
  "SELECT count(DISTINCT x.master_patient_id) FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id WHERE p.source_patient_id IN ('SEED-0001', 'PMI-0004')" "2"

# Fresh synthetic people every run (random surname + birth date), so re-runs never collide with
# earlier runs' merges or exclusions: A and A2 are the same person in two feeds (deterministic),
# B has day/month swapped and no phone (review band), T is A's opposite-sex twin (review band).
read -r SFX LAST DOB DOB_SWAP PHONE4 < <(python3 -c "
import random, string
r = random.SystemRandom()
sfx = ''.join(r.choice(string.ascii_lowercase) for _ in range(8))
d = m = 0
while d == m: d, m = r.randint(1, 12), r.randint(1, 12)
y = r.randint(1930, 1999)
print(sfx, 'Smk' + sfx, f'{y}-{m:02d}-{d:02d}', f'{y}-{d:02d}-{m:02d}', r.randint(1000, 9999))")
psql_id() { docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atqc "$1" | head -1; }
ins_patient() { # source id first gender dob address phone
  psql_id "INSERT INTO silver.patients (source_system, source_patient_id, mrn, first_name, last_name, date_of_birth, gender, address_line1, city, state, postal_code, phone) VALUES ('$1', 'SMK-$SFX-$2', 'M$2$SFX', '$3', '$LAST', '$5', '$4', '$6', 'Atlanta', 'GA', '30301', $7) RETURNING id"
}
SA="$(ins_patient SMOKE_EPIC A Maria female "$DOB" '12 Harbor Rd' "'404-555-$PHONE4'")"
SA2="$(ins_patient SMOKE_CLAIMS A2 Maria female "$DOB" '12 Harbor Road' "'(404) 555-$PHONE4'")"
SB="$(ins_patient SMOKE_LAB B Maria female "$DOB_SWAP" '88 Peachtree St' NULL)"
ST="$(ins_patient SMOKE_EPIC T Mario male "$DOB" '12 Harbor Rd' "'404-555-$PHONE4'")"
for id in "$SA" "$SA2" "$SB" "$ST"; do
  fc_curl -s -o /dev/null -X POST "$FHIR_COMPLIANCE_URL/api/fhir/build/patients/$id"
done
master_of() { psql_id "SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = $1"; }
MB_ORIG="$(master_of "$SB")"; MT="$(master_of "$ST")"
echo "  (synthetic surname $LAST, DOB $DOB; Silver ids A=$SA A2=$SA2 B=$SB T=$ST)"

CORR="smoke-pmi-$SFX"
pmi_run="$(fc_curl -s -o "$TMP/pmi_run.json" -D - -X POST "$PMI/match/run" -H "X-Correlation-Id: $CORR" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
if [[ "$pmi_run" == "$CORR" ]]; then
  echo "  PASS  run echoes the caller's X-Correlation-Id ($pmi_run)"; PASS=$((PASS + 1))
else
  echo "  FAIL  run echoes the caller's X-Correlation-Id (got '$pmi_run')"; FAIL=$((FAIL + 1))
fi
check_json "  → 1 auto-merge, 2 review items, from this run's 4 new masters" "$TMP/pmi_run.json" \
  "(d['run']['provisionalMasters'] >= 4, d['run']['autoMerges'], d['run']['queuedForReview'])" "(True, 1, 2)"
MA="$(master_of "$SA")"
check_sql "A + A2 (same person, two feeds) share one MATCHED master, confidence 1.0000" \
  "SELECT (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = $SA2) = $MA AND match_status = 'MATCHED' AND match_confidence_score = 1 FROM gold.master_patients WHERE id = $MA" "t"
if docker logs clinicalharmony-fhir-compliance-service 2>&1 | grep -q "\[$CORR\].*Merged master"; then
  echo "  PASS  GoldWriter logged the merge under the PMI's correlation id [$CORR]"; PASS=$((PASS + 1))
else
  echo "  FAIL  GoldWriter logged the merge under the PMI's correlation id (no [$CORR] 'Merged master' line)"; FAIL=$((FAIL + 1))
fi

fc_curl -s "$PMI/candidates" > "$TMP/pmi_pending.json"
CB="$(python3 -c "import json; print(next(c['id'] for c in json.load(open('$TMP/pmi_pending.json')) if $SB in (c['silverPatientAId'], c['silverPatientBId'])))")"
CT="$(python3 -c "import json; print(next(c['id'] for c in json.load(open('$TMP/pmi_pending.json')) if $ST in (c['silverPatientAId'], c['silverPatientBId'])))")"
check_json "day/month-swapped record queued for review at 0.6471" "$TMP/pmi_pending.json" \
  "next(c['score'] for c in d if c['id'] == $CB)" "0.6471"
check_json "opposite-sex twin queued for review at 0.7143 (gender DISAGREE kept it from auto-merging)" "$TMP/pmi_pending.json" \
  "next((c['score'], [f['outcome'] for f in c['breakdown']['fields'] if f['field'] == 'gender'][0]) for c in d if c['id'] == $CT)" "(0.7143, 'DISAGREE')"
check_http "GET /api/pmi/score explains the twin pair" GET "$PMI/score?a=$SA&b=$ST" 200
check_json "  → REVIEW, per-field breakdown" "$TMP/last_response.json" "(d['decision'], len(d['fields']))" "('REVIEW', 7)"

check_http "reject the twin → 200" POST "$PMI/candidates/$CT/reject" 200 "application/json" '{"reviewer": "smoke", "note": "twin brother"}'
check_json "  → REJECTED, twin's master confirmed as its own person" "$TMP/last_response.json" \
  "(d['candidate']['status'], d['confirmedMasterIds'] == [$MT])" "('REJECTED', True)"
check_sql "  → twin master MATCHED; pair excluded from future runs" \
  "SELECT (SELECT match_status FROM gold.master_patients WHERE id = $MT) || ' ' || (SELECT reason FROM pmi.match_exclusions WHERE $ST IN (silver_patient_a_id, silver_patient_b_id))" "MATCHED REVIEW_REJECTED"
check_http "rejecting it again → 409" POST "$PMI/candidates/$CT/reject" 409 "application/json" '{"reviewer": "smoke"}'

check_http "accept the day/month swap → 200 (MANUAL merge via GoldWriter)" POST "$PMI/candidates/$CB/accept" 200 "application/json" '{"reviewer": "smoke", "note": "DOB keyed day-first"}'
MERGE_B="$(python3 -c "import json; print(json.load(open('$TMP/last_response.json'))['merge']['mergeId'])")"
check_sql "  → B now under A's master; its FHIR Patient re-linked to that master; PMI_MERGE lineage row" \
  "SELECT (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = $SB) = $MA AND (SELECT master_patient_id FROM gold.fhir_resources WHERE resource_type = 'Patient' AND fhir_id = 'silver-patient-$SB') = $MA AND EXISTS (SELECT 1 FROM gold.data_lineage WHERE transformation_step = 'PMI_MERGE' AND target_table = 'master_patient_merges' AND target_id = $MERGE_B)" "t"
check_http "GET master view" GET "$PMI/masters/$MA" 200
check_json "  → 3 sources, 3 FHIR Patients, 2 live merges, nothing pending" "$TMP/last_response.json" \
  "(len(d['sources']), len([r for r in d['fhirResources'] if r['resourceType'] == 'Patient']), len([m for m in d['merges'] if m['unmergedAt'] is None]), len(d['pendingCandidates']))" "(3, 3, 2, 0)"

check_http "unmerge B → 200" POST "$PMI/merges/$MERGE_B/unmerge" 200 "application/json" '{"reviewer": "smoke", "reason": "different person after chart review"}'
check_json "  → B excluded from both of A's records" "$TMP/last_response.json" "d['exclusionsAdded']" "2"
check_sql "  → B back on its own active MATCHED master, its FHIR Patient with it" \
  "SELECT (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = $SB) = $MB_ORIG AND is_active AND match_status = 'MATCHED' AND (SELECT master_patient_id FROM gold.fhir_resources WHERE resource_type = 'Patient' AND fhir_id = 'silver-patient-$SB') = $MB_ORIG FROM gold.master_patients WHERE id = $MB_ORIG" "t"
check_http "unmerging it again → 409" POST "$PMI/merges/$MERGE_B/unmerge" 409 "application/json" '{"reviewer": "smoke", "reason": "again"}'
fc_curl -s -o /dev/null -X POST "$PMI/match/run"
check_sql "a later run respects the human decisions (B not re-queued or re-merged)" \
  "SELECT count(*) FROM pmi.match_candidates WHERE status = 'PENDING' AND $SB IN (silver_patient_a_id, silver_patient_b_id)" "0"

check_http "unknown master → 404" GET "$PMI/masters/999999999" 404
check_http "merging a MERGED master → 409" POST "$PMI/merge" 409 "application/json" \
  "{\"survivorMasterId\": $MA, \"absorbedMasterId\": $(psql_id "SELECT absorbed_master_id FROM gold.master_patient_merges WHERE survivor_master_id = $MA AND unmerged_at IS NULL LIMIT 1"), \"reviewer\": \"smoke\"}"
SAVED_TOKEN="$TOKEN"
TOKEN=""
check_http "no token → 401" GET "$PMI/masters" 401
TOKEN="$(get_token "system/*.rs")"
check_http "read-only token can read masters (200)" GET "$PMI/masters?status=MATCHED&limit=5" 200
check_http "read-only token cannot run matching (403)" POST "$PMI/match/run" 403
check_http "read-only token cannot call GoldWriter's master endpoints directly (403)" POST \
  "$FHIR_COMPLIANCE_URL/api/fhir/gold/masters/$MA/confirm" 403 "application/json" '{"decidedBy": "smoke"}'
TOKEN="$SAVED_TOKEN"

section "16. api-gateway — dashboard BFF (Phase 7)"
# Needs docs/db-init/22 (read-only role) and the Phase 7 realm (dashboard clients, roles, users dana/vic).
GW="$GATEWAY_URL/api/v1"
# DEV-ONLY password grant (client clinicalharmony-dashboard-dev) — the real UI uses Auth Code + PKCE.
user_token() {
  curl -s -d grant_type=password -d client_id=clinicalharmony-dashboard-dev -d "username=$1" -d "password=$1-dev-password" "$TOKEN_URL" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
}
DANA="$(user_token dana)"   # dashboard-viewer + data-steward
VIC="$(user_token vic)"     # dashboard-viewer only
SAVED_TOKEN="$TOKEN"
TOKEN=""
check_http "gateway health is public" GET "$GATEWAY_URL/actuator/health" 200
check_http "login config is public" GET "$GW/config" 200
check_json "  → PKCE client clinicalharmony-dashboard" "$TMP/last_response.json" "(d['auth']['clientId'], d['auth']['pkce'])" "('clinicalharmony-dashboard', 'S256')"
check_http "no token → 401" GET "$GW/summary" 401
check_json "  → RFC 7807 problem with a correlation id" "$TMP/last_response.json" \
  "(d['type'], d['status'], d['instance'], len(d['correlationId']) > 0)" "('urn:clinicalharmony:problem:unauthorized', 401, '/api/v1/summary', True)"
TOKEN="$FULL_TOKEN"
check_http "a SERVICE token (aud clinicalharmony-fhir) is not a dashboard user → 401" GET "$GW/summary" 401

TOKEN="$VIC"
check_http "vic (viewer) GET /me" GET "$GW/me" 200
check_json "  → viewer, cannot act" "$TMP/last_response.json" "(d['username'], d['roles'], d['canAct'])" "('vic', ['dashboard-viewer'], False)"
check_http "summary" GET "$GW/summary" 200
check_json "  → Gold FHIR count matches the database" "$TMP/last_response.json" "d['fhirResources']" \
  "$(psql_id "SELECT count(*) FROM gold.fhir_resources")"
check_http "services/health" GET "$GW/services/health" 200
check_json "  → all 5 services + the gateway's DB UP" "$TMP/last_response.json" "(d['status'], len(d['services']), d['degraded'])" "('UP', 6, [])"

MARIA="$(psql_id "SELECT x.master_patient_id FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id WHERE p.source_patient_id = 'SEED-0001'")"
check_http "patient search q=garcia" GET "$GW/patients?q=garcia&size=50" 200
check_json "  → finds SEED-0001's master" "$TMP/last_response.json" "any(m['id'] == $MARIA for m in d['items'])" "True"
check_http "patient timeline" GET "$GW/patients/$MARIA/timeline" 200
check_json "  → one event per Silver condition/observation/medication of every linked record" "$TMP/last_response.json" "d['eventCount']" \
  "$(psql_id "WITH l AS (SELECT silver_patient_id id FROM gold.patient_source_xref WHERE master_patient_id = $MARIA) SELECT (SELECT count(*) FROM silver.conditions WHERE patient_id IN (SELECT id FROM l)) + (SELECT count(*) FROM silver.observations WHERE patient_id IN (SELECT id FROM l)) + (SELECT count(*) FROM silver.medications WHERE patient_id IN (SELECT id FROM l))")"
check_json "  → E11.9 is a known ICD-10 code, onset 2015-06-01, linked to its Gold Condition" "$TMP/last_response.json" \
  "next((e['codeKnown'], e['occurredAt'][:10], e['fhirReference'].startswith('Condition/')) for e in d['events'] if e['code'] == 'E11.9')" "(True, '2015-06-01', True)"

check_http "ontology coverage" GET "$GW/ontology/coverage" 200
check_json "  → 4 terminologies; ICD-10's blank seed code reported unknown" "$TMP/last_response.json" \
  "([t['terminology'] for t in d['terminologies']], '' in [u['code'] for u in d['terminologies'][0]['topUnknownCodes']])" "(['ICD-10', 'SNOMED-CT', 'LOINC', 'RxNorm'], True)"
check_http "ICD-10 lookup proxied to ontology-service" GET "$GW/ontology/codes/icd10/E11.9" 200
check_http "unknown ICD-10 code → 404 problem from ontology-service" GET "$GW/ontology/codes/icd10/ZZZ99" 404
check_json "  → service + downstream status named" "$TMP/last_response.json" "(d['type'], d['service'], d['downstreamStatus'])" \
  "('urn:clinicalharmony:problem:downstream-rejected', 'ontology-service', 404)"
check_http "unsupported lookup system → 400" GET "$GW/ontology/codes/snomed/44054006" 400

check_http "validation summary" GET "$GW/validation/summary" 200
check_json "  → total matches silver.validation_reports" "$TMP/last_response.json" "d['total']" "$(psql_id "SELECT count(*) FROM silver.validation_reports")"
check_http "validation findings severity=ERROR" GET "$GW/validation/findings?severity=ERROR&size=10" 200
check_json "  → only ERRORs" "$TMP/last_response.json" "set(f['severity'] for f in d['items'])" "{'ERROR'}"
check_http "rule catalog" GET "$GW/validation/rules" 200
check_json "  → owners + toggleability" "$TMP/last_response.json" \
  "[next((r['evaluatedBy'], r['toggleable']) for r in d if r['code'] == c) for c in ('CODE-001', 'LAB_REFERENCE_RANGE', 'USCORE_PATIENT_COMPLETENESS')]" \
  "[('ontology-service', False), ('rules-engine-service', True), ('fhir-compliance-service', True)]"
check_http "viewer cannot toggle a rule → 403" PUT "$GW/validation/rules/LAB_REFERENCE_RANGE/toggle" 403
check_json "  → names the role" "$TMP/last_response.json" "d['requiredRole']" "data-steward"

check_http "FHIR resource types" GET "$GW/fhir/resource-types" 200
check_http "Gold Patient for SEED-0001" GET "$GW/fhir/resources/Patient/silver-patient-$(psql_id "SELECT id FROM silver.patients WHERE source_patient_id = 'SEED-0001'")" 200
check_json "  → resource + validator notes + FHIR_MAP lineage" "$TMP/last_response.json" \
  "(d['resource']['resourceType'], d['summary']['usCoreCompliant'], 'FHIR_MAP' in [r['step'] for r in d['lineage']])" "('Patient', True, True)"
check_http "viewer may use the HL7 validator through the gateway" POST "$GW/fhir/validate" 200 "application/json" '{"resourceType":"Patient","id":"smoke-gw"}'
check_json "  → non-compliant Patient explained" "$TMP/last_response.json" "(d['compliant'], d['errorCount'] > 0)" "(False, True)"
check_http "lineage for SEED-0001's master" GET "$GW/lineage?masterPatientId=$MARIA" 200
check_json "  → PMI and FHIR_MAP steps" "$TMP/last_response.json" "sorted({'PMI_PROVISIONAL', 'FHIR_MAP'} & {r['step'] for r in d['records']})" "['FHIR_MAP', 'PMI_PROVISIONAL']"
check_http "lineage without a target → 400" GET "$GW/lineage" 400
check_http "quarantined Bronze messages" GET "$GW/bronze/messages?status=QUARANTINED&size=200" 200
check_json "  → total matches bronze.raw_messages" "$TMP/last_response.json" "d['total']" \
  "$(psql_id "SELECT count(*) FROM bronze.raw_messages WHERE processing_status = 'QUARANTINED'")"
check_http "unknown gateway path → 404 problem" GET "$GW/does-not-exist" 404

# Steward actions: a fresh opposite-sex-twin pair lands in the PMI review queue; dana rejects it via the gateway.
# Fresh suffix, birth date and phone so blocking can't pair them with section 15's people.
read -r SFX DOB PHONE4 < <(python3 -c "
import random, string
r = random.SystemRandom()
print(''.join(r.choice(string.ascii_lowercase) for _ in range(8)), f'{r.randint(1930, 1999)}-{r.randint(1, 12):02d}-{r.randint(1, 28):02d}', r.randint(1000, 9999))")
LAST="Gw$SFX"
GA="$(ins_patient SMOKE_EPIC GA Maria female "$DOB" '12 Harbor Rd' "'404-555-$PHONE4'")"
GT="$(ins_patient SMOKE_EPIC GT Mario male "$DOB" '12 Harbor Rd' "'404-555-$PHONE4'")"
TOKEN="$SAVED_TOKEN"
for id in "$GA" "$GT"; do fc_curl -s -o /dev/null -X POST "$FHIR_COMPLIANCE_URL/api/fhir/build/patients/$id"; done
TOKEN="$VIC"
check_http "viewer cannot start a matching run → 403" POST "$GW/pmi/match/run" 403
TOKEN="$DANA"
GCORR="smoke-gw-$SFX"
gw_run="$(fc_curl -s -o "$TMP/gw_run.json" -D - -X POST "$GW/pmi/match/run" -H "X-Correlation-Id: $GCORR" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
check_json "dana's matching run via the gateway queues the twin for review" "$TMP/gw_run.json" "(d['run']['status'], d['run']['queuedForReview'] >= 1)" "('COMPLETED', True)"
if [[ "$gw_run" == "$GCORR" ]] && docker logs clinicalharmony-patient-index-service 2>&1 | grep -q "\[$GCORR\].*PMI run"; then
  echo "  PASS  correlation id [$GCORR] echoed by the gateway and logged by patient-index-service"; PASS=$((PASS + 1))
else
  echo "  FAIL  correlation id [$GCORR] not echoed ('$gw_run') or not in patient-index-service's log"; FAIL=$((FAIL + 1))
fi
fc_curl -s "$GW/pmi/candidates" > "$TMP/gw_pending.json"
GC="$(python3 -c "import json; print(next(c['id'] for c in json.load(open('$TMP/gw_pending.json')) if $GT in (c['silverPatientAId'], c['silverPatientBId'])))")"
check_http "dana rejects the twin via the gateway (body tries to spoof the reviewer)" POST "$GW/pmi/candidates/$GC/reject" 200 "application/json" '{"note": "twin brother", "reviewer": "mallory"}'
check_sql "  → recorded reviewer is the token's user, not the body's" "SELECT status || ' ' || reviewed_by FROM pmi.match_candidates WHERE id = $GC" "REJECTED dana"
check_http "rejecting it again → 409 passed through" POST "$GW/pmi/candidates/$GC/reject" 409 "application/json" '{}'
check_json "  → problem names patient-index-service" "$TMP/last_response.json" "(d['service'], d['downstreamStatus'])" "('patient-index-service', 409)"

check_http "dana toggles LAB_REFERENCE_RANGE off (routed to rules-engine-service)" PUT "$GW/validation/rules/LAB_REFERENCE_RANGE/toggle" 200
check_json "  → inactive" "$TMP/last_response.json" "(d['active'], d['evaluatedBy'])" "(False, 'rules-engine-service')"
check_http "  … and back on" PUT "$GW/validation/rules/LAB_REFERENCE_RANGE/toggle" 200
check_sql "  → rule active again in the catalog" "SELECT is_active FROM ontology.clinical_rules WHERE rule_code = 'LAB_REFERENCE_RANGE'" "t"
check_http "CODE-001 has no toggle owner → 409" PUT "$GW/validation/rules/CODE-001/toggle" 409

ro_write="$(docker exec -e PGPASSWORD=gateway-ro-dev-password "$PG_CONTAINER" psql -h localhost -U clinicalharmony_gateway_ro -d clinicalharmony -Atc "DELETE FROM silver.validation_reports WHERE id = -1" 2>&1)"
if [[ "$ro_write" == *"read-only transaction"* ]]; then
  echo "  PASS  the gateway's DB login cannot write"; PASS=$((PASS + 1))
else
  echo "  FAIL  the gateway's DB login cannot write (got: $ro_write)"; FAIL=$((FAIL + 1))
fi
cors_ok="$(curl -s -o /dev/null -D - -X OPTIONS "$GW/summary" -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: authorization" | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')"
cors_bad="$(curl -s -o /dev/null -w "%{http_code}" -X OPTIONS "$GW/summary" -H "Origin: http://evil.example" -H "Access-Control-Request-Method: GET")"
if [[ "$cors_ok" == "http://localhost:3000" && "$cors_bad" == "403" ]]; then
  echo "  PASS  CORS preflight: dashboard origin allowed, others refused"; PASS=$((PASS + 1))
else
  echo "  FAIL  CORS preflight (allowed origin '$cors_ok', foreign origin HTTP $cors_bad)"; FAIL=$((FAIL + 1))
fi
TOKEN="$SAVED_TOKEN"

echo
echo "=== Summary: $PASS passed, $FAIL failed ==="
[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
