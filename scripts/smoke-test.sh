#!/usr/bin/env bash
# End-to-end smoke test for ingestion-service, ontology-service, rules-engine-service,
# fhir-compliance-service, patient-index-service, api-gateway, dashboard-ui, (Phase 9a) the
# MLLP → Kafka → bronze-listener-service path driven by data-generator, (Phase 9b) the
# bronze.ingested → silver-transformer-service → silver.patient.changed path, and (Phase 9c) the
# silver.patient.changed → gold-builder → Gold → gold.patient.changed → pmi-matcher path, plus the Data
# Lineage layer browser's read models (section 21), (Phase 9d part 1) REST intake over Kafka for
# HL7 v2 / FHIR JSON / claims CSV / C-CDA (section 22) and (9d part 2) every feed into Silver from the full
# generator: cross-feed duplicates, planted defects, backlog sweeper, --reset, --trickle (section 23), and (9e) Splunk:
# logs + metrics + consumer lag arriving, dashboards + alerts provisioned, each alert firing (section 24).
# Assumes the stack is already up: `docker-compose up -d` from the repo root.
# Usage: ./scripts/smoke-test.sh
set -uo pipefail

INGESTION_URL="${INGESTION_URL:-http://localhost:8081}"
ONTOLOGY_URL="${ONTOLOGY_URL:-http://localhost:8082}"
RULES_URL="${RULES_URL:-http://localhost:8083}"
FHIR_COMPLIANCE_URL="${FHIR_COMPLIANCE_URL:-http://localhost:8084}"
PMI_URL="${PMI_URL:-http://localhost:8085}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:3000}"
BRONZE_URL="${BRONZE_URL:-http://localhost:8086}"
SILVER_URL="${SILVER_URL:-http://localhost:8087}"
KAFBAT_URL="${KAFBAT_URL:-http://localhost:8090}"
MLLP_HOST="${MLLP_HOST:-localhost}"
MLLP_PORT="${MLLP_PORT:-8887}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-clinicalharmony-kafka}"
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
for pair in "ingestion:$INGESTION_URL" "ontology:$ONTOLOGY_URL" "rules-engine:$RULES_URL" "fhir-compliance:$FHIR_COMPLIANCE_URL" "patient-index:$PMI_URL" "bronze-listener:$BRONZE_URL" "silver-transformer:$SILVER_URL"; do
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

# Since 9d the REST paths publish to Kafka: 202 = passed the syntax check and Kafka confirmed it, 422 = failed
# it (published anyway; bronze-listener-service quarantines it). Resending a fixture is fine: Bronze skips the
# duplicate. Section 22 follows each payload into Bronze.
section "1. ingestion-service — HL7 v2 (REST)"
check_http "valid ADT^A01 accepted (202, on Kafka)" POST "$INGESTION_URL/api/ingestion/hl7" 202 "text/plain" "$FIXTURES/hl7_valid.txt"
check_http "malformed HL7 refused (422, still published for quarantine)" POST "$INGESTION_URL/api/ingestion/hl7" 422 "text/plain" "$FIXTURES/hl7_malformed.txt"

section "2. ingestion-service — FHIR JSON"
check_http "valid Patient resource accepted (202)" POST "$INGESTION_URL/api/ingestion/fhir" 202 "application/json" "$FIXTURES/fhir_valid_patient.json"
check_http "resource with no resourceType refused (422)" POST "$INGESTION_URL/api/ingestion/fhir" 422 "application/json" "$FIXTURES/fhir_malformed.json"

section "3. ingestion-service — Claims CSV"
check_http "valid 2-row claims file accepted (202)" POST "$INGESTION_URL/api/ingestion/claims" 202 "text/csv" "$FIXTURES/claims_valid.csv"
check_http "ragged claims file refused whole (422)" POST "$INGESTION_URL/api/ingestion/claims" 422 "text/csv" "$FIXTURES/claims_ragged.csv"

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
check_sql "Silver-built lineage chains run SILVER → GOLD (seed rows), or BRONZE → SILVER → GOLD (rows from Kafka, 9b)" \
  "SELECT bool_and(chain IN ('SILVER>GOLD', 'BRONZE>SILVER>GOLD')) AND bool_or(chain = 'SILVER>GOLD') FROM (SELECT (SELECT string_agg(s->>'stage', '>' ORDER BY o) FROM jsonb_array_elements(transformation_details->'steps') WITH ORDINALITY AS t(s, o)) AS chain FROM gold.data_lineage WHERE transformation_step = 'FHIR_MAP') x" "t"
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
# Since 9e every service logs JSON: the correlation id is the "requestId" (MDC) field of the line.
docker logs clinicalharmony-fhir-compliance-service > "$TMP/fc_merge.log" 2>&1
if grep "\"requestId\":\"$CORR\"" "$TMP/fc_merge.log" | grep -q "Merged master"; then
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
# A first + last name, not just "garcia": the generator adds GARCIAs on every run, so a one-word search outgrows a page.
check_http "patient search q=maria garcia" GET "$GW/patients?q=maria%20garcia&size=50" 200
check_json "  → finds SEED-0001's master" "$TMP/last_response.json" "any(m['id'] == $MARIA for m in d['items'])" "True"
check_http "patient search by MRN q=mrn100001 (any case)" GET "$GW/patients?q=mrn100001" 200
check_json "  → exactly SEED-0001's master" "$TMP/last_response.json" "[m['id'] for m in d['items']]" "[$MARIA]"
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
# Phase 10 list controls: every paged list takes a number range (idFrom/idTo, a reversed range is swapped) and an order
QLO=$(psql_id "SELECT min(id) FROM bronze.raw_messages WHERE processing_status = 'QUARANTINED'")
QHI=$(psql_id "SELECT max(id) FROM bronze.raw_messages WHERE processing_status = 'QUARANTINED'")
QFIRST=$(psql_id "SELECT id FROM bronze.raw_messages WHERE processing_status = 'QUARANTINED' ORDER BY received_at, id LIMIT 1")
check_http "Bronze list: number range + oldest first" GET "$GW/bronze/messages?status=QUARANTINED&idFrom=$QHI&idTo=$QLO&order=oldest&size=200" 200
check_json "  → reversed range read as $QLO–$QHI, every quarantined row, oldest first" "$TMP/last_response.json" \
  "(d['total'], d['items'][0]['id'])" \
  "($(psql_id "SELECT count(*) FROM bronze.raw_messages WHERE processing_status = 'QUARANTINED'"), ${QFIRST:-0})"
check_http "Bronze list: 'name' is not an order for messages → 400" GET "$GW/bronze/messages?order=name" 400
check_http "Gold masters: newest master number first" GET "$GW/patients?order=newest&size=2" 200
check_json "  → the highest active master id on top" "$TMP/last_response.json" "d['items'][0]['id']" \
  "$(psql_id "SELECT max(id) FROM gold.master_patients WHERE is_active")"
CLAIM_LINE=$(psql_id "SELECT raw_message_id FROM silver.claims WHERE raw_message_id IS NOT NULL ORDER BY id DESC LIMIT 1")
check_http "Bronze claims line #${CLAIM_LINE:-0} (no PID segment)" GET "$GW/bronze/messages?idFrom=${CLAIM_LINE:-0}&idTo=${CLAIM_LINE:-0}" 200
check_json "  → names its member (the Silver patient it built)" "$TMP/last_response.json" \
  "(d['items'][0]['silverPatientId'], bool(d['items'][0]['patientName']))" \
  "($(psql_id "SELECT patient_id FROM silver.claims WHERE raw_message_id = ${CLAIM_LINE:-0} ORDER BY id LIMIT 1"), True)"
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
docker logs clinicalharmony-patient-index-service > "$TMP/pmi_run.log" 2>&1
if [[ "$gw_run" == "$GCORR" ]] && grep "\"requestId\":\"$GCORR\"" "$TMP/pmi_run.log" | grep -q "PMI run"; then
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

# dashboard ordering fixes (branch pmi-review-recent-decisions)
fc_curl -s "$GW/pmi/candidates?status=DECIDED&limit=10" > "$TMP/gw_decided.json"
check_json "Recently decided: dana's reject of the twin is the newest decision" "$TMP/gw_decided.json" "(d[0]['id'], d[0]['reviewedBy'])" "($GC, 'dana')"
check_json "  → newest decision first, only ACCEPTED/REJECTED" "$TMP/gw_decided.json" \
  "all(a['reviewedAt'] >= b['reviewedAt'] for a, b in zip(d, d[1:])) and {c['status'] for c in d} <= {'ACCEPTED', 'REJECTED'}" "True"
GRUN="$(python3 -c "import json; print(json.load(open('$TMP/gw_run.json'))['run']['id'])")"
fc_curl -s "$GW/pmi/candidates?status=QUEUED&runId=$GRUN" > "$TMP/gw_run_queued.json"
check_json "run filter: QUEUED candidates of dana's run include the twin, nothing auto-merged" "$TMP/gw_run_queued.json" \
  "($GC in [c['id'] for c in d], all(c['runId'] == $GRUN and c['status'] != 'AUTO_MERGED' for c in d))" "(True, True)"
fc_curl -s "$GW/pmi/candidates?status=PENDING&limit=500" > "$TMP/gw_pending_sorted.json"
check_json "PENDING stays highest score first" "$TMP/gw_pending_sorted.json" "all(a['score'] >= b['score'] for a, b in zip(d, d[1:]))" "True"
fc_curl -s "$GW/fhir/resources?type=Patient&q=silver-patient-&sort=resource&size=200" > "$TMP/gw_fhir_sorted.json"
check_json "FHIR Viewer: silver-patient-N ids sorted by number, not text" "$TMP/gw_fhir_sorted.json" \
  "(lambda n: n == sorted(n))([int(r['fhirId'].rsplit('-', 1)[1]) for r in d['items']])" "True"
fc_curl -s "$GW/fhir/resources?type=Patient&q=silver-patient-&size=200&sort=resource&dir=desc" > "$TMP/gw_fhir_desc.json"
check_json "  → and descending on request" "$TMP/gw_fhir_desc.json" \
  "(lambda n: n == sorted(n, reverse=True))([int(r['fhirId'].rsplit('-', 1)[1]) for r in d['items']])" "True"
check_http "  → an unknown sort column is refused → 400" GET "$GW/fhir/resources?sort=fhir_id" 400
fc_curl -s "$GW/lineage/summary" > "$TMP/gw_lineage_summary.json"
check_json "Data Lineage summary in pipeline order (FHIR_MAP first, SEED/API last)" "$TMP/gw_lineage_summary.json" \
  "(lambda s: s[0] == 'FHIR_MAP' and s.index('PMI_PROVISIONAL') < s.index('PMI_MATCH') < s.index('SEED_LOAD') < s.index('API_GENERATE'))([r['step'] for r in d])" "True"

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

section "17. dashboard-ui — static SPA behind nginx (Phase 8)"
# The UI itself is covered by Vitest (dashboard-ui/test) and a manual click-through
# (dashboard-ui/CHECKLIST.md). Here: what nginx serves, the runtime config, the security headers,
# and the two things the browser's login and API calls depend on outside this repo's JS —
# Keycloak accepting the dashboard's PKCE request, and CORS on the gateway and Keycloak.
DASH="$DASHBOARD_URL"
KC_OIDC="$KEYCLOAK_URL/realms/clinicalharmony/protocol/openid-connect"
# header_of FILE NAME — value of one response header from a curl -D dump (case-insensitive)
header_of() { tr -d '\r' < "$1" | awk -F': ' -v h="$(echo "$2" | tr '[:upper:]' '[:lower:]')" 'tolower($1)==h{print $2}' | tail -1; }
pass_if() {  # pass_if NAME CONDITION_RESULT(0/1) DETAIL
  if [[ "$2" == "0" ]]; then echo "  PASS  $1"; PASS=$((PASS + 1)); else echo "  FAIL  $1 ($3)"; FAIL=$((FAIL + 1)); fi
}

code="$(curl -s -o "$TMP/dash_index.html" -D "$TMP/dash_index.h" -w "%{http_code}" "$DASH/")"
[[ "$code" == "200" ]] && grep -q '<div id="root">' "$TMP/dash_index.html"; pass_if "GET / serves the SPA shell" $? "HTTP $code"
code="$(curl -s -o "$TMP/dash_deep.html" -w "%{http_code}" "$DASH/patients/1?types=CONDITION")"
[[ "$code" == "200" ]] && cmp -s "$TMP/dash_index.html" "$TMP/dash_deep.html"; pass_if "deep link /patients/1 falls back to index.html" $? "HTTP $code"
[[ "$(header_of "$TMP/dash_index.h" Cache-Control)" == "no-cache" ]]; pass_if "  → index.html is revalidated (Cache-Control: no-cache)" $? "$(header_of "$TMP/dash_index.h" Cache-Control)"
csp="$(header_of "$TMP/dash_index.h" Content-Security-Policy)"
[[ "$csp" == *"connect-src 'self' $GATEWAY_URL $KEYCLOAK_URL;"* && "$csp" == *"script-src 'self';"* && "$csp" == *"frame-ancestors 'none'"* ]]
pass_if "CSP limits scripts to self and connections to the gateway + Keycloak" $? "$csp"
[[ "$(header_of "$TMP/dash_index.h" X-Content-Type-Options)" == "nosniff" && "$(header_of "$TMP/dash_index.h" X-Frame-Options)" == "DENY" && "$(header_of "$TMP/dash_index.h" Referrer-Policy)" == "no-referrer" ]]
pass_if "nosniff / X-Frame-Options DENY / Referrer-Policy no-referrer" $? "missing header"
[[ "$(header_of "$TMP/dash_index.h" Server)" == "nginx" ]]; pass_if "nginx doesn't reveal its version" $? "Server: $(header_of "$TMP/dash_index.h" Server)"

curl -s -D "$TMP/dash_cfg.h" -o "$TMP/dash_config.js" "$DASH/config.js"
grep -q "gatewayUrl: '$GATEWAY_URL'" "$TMP/dash_config.js" && [[ "$(header_of "$TMP/dash_cfg.h" Cache-Control)" == "no-store" ]]
pass_if "config.js carries the gateway URL from the container env, never cached" $? "$(cat "$TMP/dash_config.js")"
bundle="$(grep -oE '/assets/index-[A-Za-z0-9_-]+\.js' "$TMP/dash_index.html" | head -1)"
code="$(curl -s -o /dev/null -D "$TMP/dash_js.h" -w "%{http_code}" "$DASH$bundle")"
[[ -n "$bundle" && "$code" == "200" && "$(header_of "$TMP/dash_js.h" Cache-Control)" == *immutable* ]]
pass_if "hashed bundle $bundle served with an immutable cache" $? "HTTP $code, $(header_of "$TMP/dash_js.h" Cache-Control)"
code="$(curl -s -o /dev/null -w "%{http_code}" "$DASH/assets/does-not-exist.js")"
[[ "$code" == "404" ]]; pass_if "a missing asset is a 404, not index.html" $? "HTTP $code"
health="$(docker inspect -f '{{.State.Health.Status}}' clinicalharmony-dashboard-ui 2>/dev/null)"
[[ "$(curl -s "$DASH/healthz")" == "ok" && "$health" == "healthy" ]]; pass_if "/healthz ok and container healthy" $? "docker health: $health"

# Login: Keycloak must accept exactly the request oidc-client-ts builds (Auth Code + S256 PKCE,
# redirect /callback), and refuse it without PKCE or with a foreign redirect.
verifier="$(python3 -c 'import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode())')"
challenge="$(python3 -c "import base64,hashlib;print(base64.urlsafe_b64encode(hashlib.sha256(b'$verifier').digest()).rstrip(b'=').decode())")"
auth_q="client_id=clinicalharmony-dashboard&response_type=code&scope=openid&state=smoke"
cb="redirect_uri=$(python3 -c "import urllib.parse;print(urllib.parse.quote('$DASH/callback',safe=''))")"
code="$(curl -s -o "$TMP/kc_login.html" -w "%{http_code}" "$KC_OIDC/auth?$auth_q&$cb&code_challenge=$challenge&code_challenge_method=S256")"
[[ "$code" == "200" ]] && grep -q 'id="username"' "$TMP/kc_login.html"; pass_if "Keycloak shows its login page for the dashboard's PKCE request" $? "HTTP $code"
loc="$(curl -s -o /dev/null -D - "$KC_OIDC/auth?$auth_q&$cb" | tr -d '\r' | awk -F': ' 'tolower($1)=="location"{print $2}')"
[[ "$loc" == *"error=invalid_request"* && "$loc" == *"code_challenge_method"* ]]; pass_if "  → without PKCE it refuses (invalid_request)" $? "$loc"
code="$(curl -s -o /dev/null -w "%{http_code}" "$KC_OIDC/auth?$auth_q&redirect_uri=http%3A%2F%2Fevil.example%2Fcallback&code_challenge=$challenge&code_challenge_method=S256")"
[[ "$code" == "400" ]]; pass_if "  → a redirect outside $DASH/* is refused" $? "HTTP $code"
kc_cors="$(curl -s -o /dev/null -D - -X OPTIONS "$KC_OIDC/token" -H "Origin: $DASH" -H "Access-Control-Request-Method: POST" | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')"
[[ "$kc_cors" == "$DASH" ]]; pass_if "Keycloak's token endpoint allows the dashboard origin (code exchange + refresh)" $? "'$kc_cors'"
gw_hdrs="$(curl -s -o /dev/null -D - -X OPTIONS "$GATEWAY_URL/api/v1/me" -H "Origin: $DASH" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: authorization,x-correlation-id" | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-headers"{print tolower($2)}')"
gw_expose="$(curl -s -o /dev/null -D - "$GATEWAY_URL/api/v1/config" -H "Origin: $DASH" | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-expose-headers"{print $2}')"
[[ "$gw_hdrs" == *authorization* && "$gw_hdrs" == *x-correlation-id* && "$gw_expose" == *X-Correlation-Id* ]]
pass_if "gateway CORS lets the UI send Authorization + X-Correlation-Id and read the echoed id" $? "allow '$gw_hdrs', expose '$gw_expose'"

section "18. Bronze over Kafka — data-generator → MLLP → Kafka → bronze-listener (Phase 9a)"
# Needs migration 23, the KRaft kafka container, and bronze-listener-service. A fresh random seed per
# run makes every payload new, so the section is rerunnable without wiping Bronze.
kexec() { docker exec "$KAFKA_CONTAINER" "$@" 2>/dev/null; }
for t in clinicalharmony.bronze.hl7v2.raw clinicalharmony.bronze.hl7v2.raw.dlt clinicalharmony.bronze.ingested; do
  parts="$(kexec kafka-topics --bootstrap-server localhost:29092 --describe --topic "$t" | grep -c 'Partition: ')"
  [[ "$parts" == "3" ]]; pass_if "topic $t exists with 3 partitions" $? "$parts partitions"
done
# Sum of LAG for a consumer group over one topic ("-" counts as 0).
group_lag() { kexec kafka-consumer-groups --bootstrap-server localhost:29092 --describe --group "$1" \
  | awk -v t="$2" '$2==t && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}'; }
wait_lag_zero() { for _ in $(seq 1 60); do [[ "$(group_lag bronze-writer clinicalharmony.bronze.hl7v2.raw)" == "0" ]] && return 0; sleep 0.5; done; return 1; }
# 9b: silver-transformer then carries every PENDING row on to PROCESSED (or FAILED)
wait_silver_lag_zero() { for _ in $(seq 1 60); do [[ "$(group_lag silver-transformer clinicalharmony.bronze.ingested)" == "0" ]] && return 0; sleep 0.5; done; return 1; }
in_list() { python3 -c "import json;print(','.join(\"'%s'\" % m['sha256'] for m in json.load(open('$1'))['messages']))"; }

SEED="$(( (RANDOM << 15) | RANDOM ))"
GEN_OUT="$TMP/generator"
python3 "$SCRIPT_DIR/../data-generator/generate.py" --count 20 --seed "$SEED" --malformed-rate 0.10 --feeds hl7 \
  --out "$GEN_OUT" --host "$MLLP_HOST" --port "$MLLP_PORT" --quiet > "$TMP/gen.out" 2>&1
gen_rc=$?
[[ $gen_rc -eq 0 ]] && grep -q "20 ACK AA" "$TMP/gen.out"
pass_if "generator sent 20 messages (seed $SEED) and every one got ACK AA — incl. the 2 malformed" $? "$(tail -1 "$TMP/gen.out")"
MANIFEST="$GEN_OUT/seed-$SEED/manifest.json"
SHAS="$(in_list "$MANIFEST")"
wait_lag_zero; pass_if "bronze-writer caught up (consumer lag 0)" $? "lag $(group_lag bronze-writer clinicalharmony.bronze.hl7v2.raw)"
check_sql "all 20 landed in bronze.raw_messages via MLLP_KAFKA" \
  "SELECT count(*) FROM bronze.raw_messages WHERE intake_channel = 'MLLP_KAFKA' AND payload_checksum IN ($SHAS)" "20"
for _ in $(seq 1 60); do
  [[ "$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS) AND processing_status = 'PENDING'")" == "0" ]] && break
  sleep 0.5
done
check_sql "  → 18 valid (PENDING in Bronze, then PROCESSED by silver-transformer in 9b) + 2 QUARANTINED, as the manifest says" \
  "SELECT string_agg(processing_status || '=' || n, ',' ORDER BY processing_status) FROM (SELECT processing_status, count(*) n FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS) GROUP BY 1) x" \
  "PROCESSED=18,QUARANTINED=2"
mismatch="$(python3 - "$MANIFEST" <<'PY'
import json, subprocess, sys
m = json.load(open(sys.argv[1]))["messages"]
rows = subprocess.run(["docker", "exec", "clinicalharmony-postgres", "psql", "-U", "clinicalharmony", "-d", "clinicalharmony", "-Atc",
    "SELECT payload_checksum || ' ' || processing_status FROM bronze.raw_messages WHERE intake_channel='MLLP_KAFKA' AND payload_checksum IN (%s)"
    % ",".join("'%s'" % x["sha256"] for x in m)], capture_output=True, text=True).stdout.split()
got = dict(zip(rows[::2], rows[1::2]))
# expectedStatus = the Bronze verdict at intake; since 9b a PENDING row moves on to PROCESSED
final = {"PENDING": "PROCESSED"}
print(sum(1 for x in m if got.get(x["sha256"]) != final.get(x["expectedStatus"], x["expectedStatus"])))
PY
)"
[[ "$mismatch" == "0" ]]; pass_if "  → every message has the status its manifest entry expects" $? "$mismatch mismatches"
check_sql "  → each row carries its correlation id + Kafka topic/partition/offset" \
  "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS) AND correlation_id LIKE 'mllp-%' AND kafka_topic = 'clinicalharmony.bronze.hl7v2.raw' AND kafka_partition BETWEEN 0 AND 2 AND kafka_offset IS NOT NULL" "20"
check_sql "  → quarantined rows keep the parse error for the data steward" \
  "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS) AND processing_status = 'QUARANTINED' AND length(error_message) > 0" "2"
check_sql "  → valid rows are tagged with their MSH-4 sending facility" \
  "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS) AND processing_status <> 'QUARANTINED' AND source_system IN ('CITY_HOSPITAL','NORTH_CLINIC','REGIONAL_LAB','CITY_PHARMACY')" "18"
for _ in $(seq 1 30); do
  [[ "$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT count(*) FROM bronze.outbox_events o JOIN bronze.raw_messages b ON b.id = o.aggregate_id WHERE b.payload_checksum IN ($SHAS) AND o.published_at IS NOT NULL")" == "18" ]] && break
  sleep 0.5
done
check_sql "outbox: one bronze.ingested event per valid row, relayed to Kafka" \
  "SELECT count(*) || '/' || count(published_at) FROM bronze.outbox_events o JOIN bronze.raw_messages b ON b.id = o.aggregate_id WHERE b.payload_checksum IN ($SHAS)" "18/18"
check_sql "  → events carry the correlation id and the hl7v2.raw event id as causation id" \
  "SELECT count(*) FROM bronze.outbox_events o JOIN bronze.raw_messages b ON b.id = o.aggregate_id WHERE b.payload_checksum IN ($SHAS) AND o.headers->>'correlation-id' = b.correlation_id AND length(o.headers->>'causation-id') = 36 AND (o.payload->>'rawMessageId')::bigint = b.id" "18"

# Idempotency: send the very same 20 files again (a sender retry storm) → all ACKed, nothing new stored.
python3 "$SCRIPT_DIR/../data-generator/generate.py" --send-dir "$GEN_OUT/seed-$SEED" --host "$MLLP_HOST" --port "$MLLP_PORT" --quiet > "$TMP/gen2.out" 2>&1
[[ $? -eq 0 ]] && grep -q "20 ACK AA" "$TMP/gen2.out"; pass_if "re-sending the same 20 files is ACKed AA again" $? "$(tail -1 "$TMP/gen2.out")"
wait_lag_zero; pass_if "  → bronze-writer consumed the repeats (lag 0)" $? ""
check_sql "  → still exactly 20 rows: repeats are skipped, not duplicated" \
  "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS)" "20"
check_sql "  → and no second bronze.ingested event" \
  "SELECT count(*) FROM bronze.outbox_events o JOIN bronze.raw_messages b ON b.id = o.aggregate_id WHERE b.payload_checksum IN ($SHAS)" "18"
check_sql "partial unique index guards the Kafka-fed rows, MLLP_KAFKA + REST_KAFKA (migration 23, widened by 26)" \
  "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_raw_messages_kafka_intake_source_checksum' AND indexdef LIKE '%MLLP_KAFKA%REST_KAFKA%'" "1"

# Raw MLLP: a malformed frame still gets AA (kept + quarantined downstream), and the ACK echoes MSH-10.
ack="$(python3 - "$MLLP_HOST" "$MLLP_PORT" "SMOKE-$SEED" <<'PY'
import socket, sys
host, port, ctrl = sys.argv[1], int(sys.argv[2]), sys.argv[3]
msg = "MSH|^~\\&|SMOKE|SMOKE_FACILITY|CH|ING|20261114||ADT^A01^ADT_A01|%s|P|2.5\rPID|1||SMK1^^^SMOKE_FACILITY^MR||TEST^SMOKE\r" % ctrl
s = socket.create_connection((host, port), timeout=15)
s.sendall(b"\x0b" + msg.encode() + b"\x1c\x0d")
buf = b""
while b"\x1c\x0d" not in buf:
    buf += s.recv(4096)
print(buf.decode().strip("\x0b\x1c\r").replace("\r", " / "))
PY
)"
[[ "$ack" == *"MSA|AA|SMOKE-$SEED"* && "$ack" == *"ACK^A01^ACK"* ]]; pass_if "MLLP ACK is AA and echoes MSH-10 (MSA-2)" $? "$ack"

# Dashboard read path: the gateway's Data Lineage → Bronze view lists the new rows.
BRONZE_ID="$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT id FROM bronze.raw_messages WHERE payload_checksum IN ($SHAS) AND processing_status='QUARANTINED' ORDER BY id LIMIT 1")"
TOKEN="$(user_token dana)"   # fresh: section 16's token may have expired by now
check_http "gateway shows a generated QUARANTINED message in Data Lineage → Bronze" GET "$GW/bronze/messages/$BRONZE_ID" 200
check_json "  → status and parse error visible to the data steward" "$TMP/last_response.json" \
  "(d['summary']['processingStatus'], len(d['summary']['errorMessage'] or '') > 0)" "('QUARANTINED', True)"
TOKEN=""

# Kafbat UI (dev console) sees the cluster and the bronze-writer group.
code="$(curl -s -o "$TMP/kafbat.json" -w "%{http_code}" "$KAFBAT_URL/api/clusters")"
[[ "$code" == "200" ]] && grep -q '"name":"clinicalharmony"' "$TMP/kafbat.json" && grep -q '"status":"ONLINE"' "$TMP/kafbat.json"
pass_if "Kafbat UI on $KAFBAT_URL sees cluster clinicalharmony online" $? "HTTP $code"
code="$(curl -s -o "$TMP/kafbat_cg.json" -w "%{http_code}" "$KAFBAT_URL/api/clusters/clinicalharmony/consumer-groups/bronze-writer")"
[[ "$code" == "200" ]] && grep -q '"groupId":"bronze-writer"' "$TMP/kafbat_cg.json"; pass_if "  → and the bronze-writer consumer group" $? "HTTP $code"

section "19. Silver over Kafka — bronze.ingested → silver-transformer → silver.* (Phase 9b)"
# Needs migration 24 and silver-transformer-service. Reuses section 18's generated seed ($SHAS), then
# sends a hand-made allergy + conflicting order pair and an unmappable message over MLLP.
for t in clinicalharmony.bronze.ingested.dlt clinicalharmony.silver.patient.changed; do
  parts="$(kexec kafka-topics --bootstrap-server localhost:29092 --describe --topic "$t" | grep -c 'Partition: ')"
  [[ "$parts" == "3" ]]; pass_if "topic $t exists with 3 partitions" $? "$parts partitions"
done
wait_silver_lag_zero; pass_if "silver-transformer caught up (consumer lag 0)" $? "lag $(group_lag silver-transformer clinicalharmony.bronze.ingested)"
check_sql "every generated ORU^R01 left its observations in Silver, linked to its Bronze row" \
  "SELECT bool_and(EXISTS (SELECT 1 FROM silver.observations o WHERE o.raw_message_id = b.id)) FROM bronze.raw_messages b WHERE b.payload_checksum IN ($SHAS) AND b.raw_payload LIKE '%|ORU^R01^%' AND b.processing_status = 'PROCESSED'" "t"
# PROCESSED only, like the ORU check above: the generator's deliberately malformed messages (e.g. EMPTY_CONTROL_ID)
# can be RDEs too — QUARANTINED in Bronze, so rightly never in Silver (failed once, 2026-11-23, on a random seed).
check_sql "every generated RDE^O11 left its medication order in Silver" \
  "SELECT coalesce(bool_and(EXISTS (SELECT 1 FROM silver.medications m WHERE m.raw_message_id = b.id)), true) FROM bronze.raw_messages b WHERE b.payload_checksum IN ($SHAS) AND b.raw_payload LIKE '%|RDE^O11^%' AND b.processing_status = 'PROCESSED'" "t"
check_sql "results and orders attach to the HOME facility's patient (PID-3.4), not the lab's or pharmacy's" \
  "SELECT count(*) FROM silver.patients WHERE source_system IN ('REGIONAL_LAB', 'CITY_PHARMACY')" "0"
check_sql "silver outbox: one silver.patient.changed event per PROCESSED row, carrying its correlation id" \
  "SELECT count(*) FROM silver.outbox_events e JOIN bronze.raw_messages b ON b.id = (e.payload->>'rawMessageId')::bigint WHERE b.payload_checksum IN ($SHAS) AND e.headers->>'correlation-id' = b.correlation_id" "18"
for _ in $(seq 1 30); do
  [[ "$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT count(*) FROM silver.outbox_events WHERE published_at IS NULL")" == "0" ]] && break
  sleep 0.5
done
check_sql "  → all relayed to Kafka" "SELECT count(*) FROM silver.outbox_events WHERE published_at IS NULL" "0"
check_sql "  → the re-sent duplicates in section 18 produced no second Silver event" \
  "SELECT count(*) = count(DISTINCT payload->>'rawMessageId') FROM silver.outbox_events" "t"

# A planted MEDICATION_ALLERGY_CONFLICT: admit with an amoxicillin allergy, then an amoxicillin order.
SMRN="SMK$SEED"
mllp_send() {  # mllp_send CONTROL_ID MESSAGE → prints the MSA-1 ack code
  python3 - "$MLLP_HOST" "$MLLP_PORT" "$2" <<'PY'
import socket, sys
host, port, msg = sys.argv[1], int(sys.argv[2]), sys.argv[3].replace("\\r", "\r")
s = socket.create_connection((host, port), timeout=15)
s.sendall(b"\x0b" + msg.encode() + b"\x1c\x0d")
buf = b""
while b"\x1c\x0d" not in buf:
    buf += s.recv(4096)
print(buf.decode().split("MSA|")[1].split("|")[0])
PY
}
PIDSEG="PID|1||$SMRN^^^SMOKE_HOSPITAL^MR||SMOKE^ALLERGY^T||19800101|F|||1 TEST ST^^SPRINGFIELD^IL^62701||5550000000"
a1="$(mllp_send "SMKA-$SEED" "MSH|^~\\&|SMOKE|SMOKE_HOSPITAL|CH|ING|20261117080000||ADT^A01^ADT_A01|SMKA-$SEED|P|2.5\r$PIDSEG\rDG1|1||N39.0^Urinary tract infection^I10||20261117080000|A\rAL1|1|DA|723^Amoxicillin^RXNORM|SV|HIVES\r")"
a2="$(mllp_send "SMKR-$SEED" "MSH|^~\\&|SMOKE_RX|SMOKE_PHARMACY|CH|ING|20261117081000||RDE^O11^RDE_O11|SMKR-$SEED|P|2.5\r$PIDSEG\rORC|NW|RX1|PH1||||||20261117081000\rRXE|^^^20261117081000^^R|723^Amoxicillin 500 MG Oral Capsule^RXNORM|500||mg^mg^UCUM\rRXR|PO^Oral^HL70162\r")"
a3="$(mllp_send "SMKF-$SEED" "MSH|^~\\&|SMOKE|SMOKE_HOSPITAL|CH|ING|20261117082000||ADT^A08^ADT_A01|SMKF-$SEED|P|2.5\rPID|1||SMKF$SEED^^^SMOKE_HOSPITAL^MR||NODOB^PATIENT||||||\r")"
[[ "$a1$a2$a3" == "AAAAAA" ]]; pass_if "allergy admit, conflicting order and an unmappable ADT all ACKed AA over MLLP" $? "$a1 $a2 $a3"
wait_lag_zero; wait_silver_lag_zero
SPID=""
for _ in $(seq 1 60); do
  SPID="$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT id FROM silver.patients WHERE source_system = 'SMOKE_HOSPITAL' AND source_patient_id = '$SMRN' AND validation_status = 'VALIDATED' AND EXISTS (SELECT 1 FROM silver.medications m WHERE m.patient_id = silver.patients.id)")"
  [[ -n "$SPID" ]] && break
  sleep 0.5
done
[[ -n "$SPID" ]]; pass_if "one Silver patient (SMOKE_HOSPITAL|$SMRN) with the allergy and the order, VALIDATED" $? "not found / not validated"
check_sql "  → silver.allergies holds AL1 (RxNorm 723, drug allergy, severe, hives)" \
  "SELECT code_system || ' ' || code || ' ' || allergy_type || ' ' || severity || ' ' || reaction FROM silver.allergies WHERE patient_id = ${SPID:-0}" "RXNORM 723 DA SV HIVES"
check_sql "  → rules-engine flagged MEDICATION_ALLERGY_CONFLICT against the Silver medication id" \
  "SELECT count(*) FROM silver.validation_reports r JOIN silver.medications m ON m.id = r.entity_id WHERE r.patient_id = ${SPID:-0} AND r.rule_code = 'MEDICATION_ALLERGY_CONFLICT' AND r.entity_type = 'MEDICATION' AND NOT r.is_resolved" "1"
check_sql "  → and ontology-service checked the real codes (no CODE-* findings for valid N39.0 / 723)" \
  "SELECT count(*) FROM silver.validation_reports WHERE patient_id = ${SPID:-0} AND rule_code LIKE 'CODE-%' AND NOT is_resolved" "0"
check_sql "  → re-validation on the 2nd message retired the 1st run's findings (only one open per problem)" \
  "SELECT count(*) FILTER (WHERE NOT is_resolved) = count(DISTINCT (rule_code, entity_type, entity_id)) FILTER (WHERE NOT is_resolved) FROM silver.validation_reports WHERE patient_id = ${SPID:-0}" "t"
FAILED_ID="$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT id FROM bronze.raw_messages WHERE raw_payload LIKE '%|SMKF-$SEED|%'")"
for _ in $(seq 1 30); do
  [[ "$(docker exec "$PG_CONTAINER" psql -U clinicalharmony -d clinicalharmony -Atc "SELECT processing_status FROM bronze.raw_messages WHERE id = ${FAILED_ID:-0}")" == "FAILED" ]] && break
  sleep 0.5
done
check_sql "the ADT without a date of birth → Bronze FAILED with the reason, nothing in Silver" \
  "SELECT processing_status || ' | ' || error_message || ' | ' || (SELECT count(*) FROM silver.patients WHERE source_patient_id = 'SMKF$SEED') FROM bronze.raw_messages WHERE id = ${FAILED_ID:-0}" \
  "FAILED | PID-7 (date of birth) is empty | 0"

TOKEN="$(user_token dana)"
check_http "gateway Validation Report lists the conflict" GET "$GW/validation/findings?ruleCode=MEDICATION_ALLERGY_CONFLICT&patientId=${SPID:-0}&resolved=false" 200
check_json "  → one ERROR finding" "$TMP/last_response.json" "(d['total'], d['items'][0]['severity'])" "(1, 'ERROR')"
check_http "gateway Data Lineage → Bronze shows the FAILED message and why" GET "$GW/bronze/messages/${FAILED_ID:-0}" 200
check_json "  → status and mapping error" "$TMP/last_response.json" "(d['summary']['processingStatus'], 'PID-7' in (d['summary']['errorMessage'] or ''))" "('FAILED', True)"
check_http "gateway Bronze list filtered to HL7 type RDE (pharmacy orders)" GET "$GW/bronze/messages?hl7Type=RDE&size=200" 200
check_json "  → only HL7 v2 RDE rows, as many as Bronze has" "$TMP/last_response.json" \
  "(d['total'] > 0, all(m['messageType'] == 'HL7V2' and m['hl7MessageType'].startswith('RDE^') for m in d['items']), d['total'])" \
  "(True, True, $(psql_id "SELECT count(*) FROM bronze.raw_messages WHERE message_type = 'HL7V2' AND raw_payload ~ '^MSH\|([^|\r\n]*\|){7}RDE(\^|\|)'"))"
TOKEN=""
code="$(curl -s -o "$TMP/kafbat_sg.json" -w "%{http_code}" "$KAFBAT_URL/api/clusters/clinicalharmony/consumer-groups/silver-transformer")"
[[ "$code" == "200" ]] && grep -q '"groupId":"silver-transformer"' "$TMP/kafbat_sg.json"; pass_if "Kafbat UI sees the silver-transformer consumer group" $? "HTTP $code"

section "20. Gold over Kafka — silver.patient.changed → gold-builder → Gold → pmi-matcher (Phase 9c)"
# Needs migration 25, gold-builder in fhir-compliance-service and pmi-matcher in patient-index-service. A fresh
# random seed whose run is guaranteed to contain one IDENTICAL and one FUZZY cross-facility duplicate (both
# registrations' first ADT valid), so the PMI outcomes are checked every run. No POST /api/fhir/build anywhere:
# Gold and matching happen on their own.
for t in clinicalharmony.silver.patient.changed.dlt clinicalharmony.gold.patient.changed clinicalharmony.gold.patient.changed.dlt; do
  parts="$(kexec kafka-topics --bootstrap-server localhost:29092 --describe --topic "$t" | grep -c 'Partition: ')"
  [[ "$parts" == "3" ]]; pass_if "topic $t exists with 3 partitions" $? "$parts partitions"
done
wait_gold_lag_zero() { for _ in $(seq 1 120); do [[ "$(group_lag gold-builder clinicalharmony.silver.patient.changed)" == "0" ]] && return 0; sleep 0.5; done; return 1; }
GSEED="$(python3 - "$SCRIPT_DIR/../data-generator" <<'PY'
import random, sys
sys.path.insert(0, sys.argv[1])
from hl7gen.messages import generate_run
r = random.SystemRandom()
while True:
    seed = r.randrange(1, 2 ** 30)
    msgs, dups = generate_run(60, seed, 0.10, 0.10)
    first = {}
    for m in msgs:
        first.setdefault(m.patient_mrn, m)
    ok = {d.kind for d in dups if all(x in first and first[x].kind == "valid" for x in (d.original_mrn, d.twin_mrn))}
    if ok == {"IDENTICAL", "FUZZY"}:
        print(seed)
        break
PY
)"
T0="$(psql_id "SELECT now()")"
python3 "$SCRIPT_DIR/../data-generator/generate.py" --count 60 --seed "$GSEED" --out "$GEN_OUT" --feeds hl7 \
  --host "$MLLP_HOST" --port "$MLLP_PORT" --quiet > "$TMP/gen3.out" 2>&1
[[ $? -eq 0 ]] && grep -q "60 ACK AA" "$TMP/gen3.out"; pass_if "generator sent 60 messages (seed $GSEED, 2 cross-facility duplicates), all ACK AA" $? "$(tail -1 "$TMP/gen3.out")"
GMAN="$GEN_OUT/seed-$GSEED/manifest.json"
GSHAS="$(in_list "$GMAN")"
wait_lag_zero; wait_silver_lag_zero
for _ in $(seq 1 60); do
  [[ "$(psql_id "SELECT count(*) FROM silver.outbox_events WHERE published_at IS NULL")" == "0" ]] && break; sleep 0.5
done
wait_gold_lag_zero; pass_if "gold-builder caught up (consumer lag 0)" $? "lag $(group_lag gold-builder clinicalharmony.silver.patient.changed)"
# This seed's Silver patients = those whose demographics came from one of its messages.
GP="SELECT p.id FROM silver.patients p JOIN bronze.raw_messages b ON b.id = p.raw_message_id WHERE b.payload_checksum IN ($GSHAS)"
check_sql "every Silver patient of the seed has a Gold FHIR Patient on a master — with no POST /api/fhir/build" \
  "SELECT count(*) > 0 AND count(*) = count(r.id) AND count(r.master_patient_id) = count(*) FROM ($GP) p LEFT JOIN gold.fhir_resources r ON r.resource_type = 'Patient' AND r.fhir_id = 'silver-patient-' || p.id" "t"
GREF="SELECT 'Patient/silver-patient-' || id FROM ($GP) q"
GRES="SELECT * FROM gold.fhir_resources WHERE resource_json #>> '{subject,reference}' IN ($GREF) OR resource_json #>> '{patient,reference}' IN ($GREF) OR fhir_id IN (SELECT 'silver-patient-' || id FROM ($GP) q)"
check_sql "  → one Gold resource per Silver row (patients + conditions + observations + medications + allergies)" \
  "SELECT (SELECT count(*) FROM ($GP) p) + (SELECT count(*) FROM silver.conditions WHERE patient_id IN ($GP)) + (SELECT count(*) FROM silver.observations WHERE patient_id IN ($GP)) + (SELECT count(*) FROM silver.medications WHERE patient_id IN ($GP)) + (SELECT count(*) FROM silver.allergies WHERE patient_id IN ($GP)) = (SELECT count(*) FROM ($GRES) r)" "t"
check_sql "  → every one validated (compliance_notes from the HL7 validator, profile set)" \
  "SELECT count(*) FROM ($GRES) r WHERE compliance_notes->>'profile' IS NULL OR compliance_notes->>'validatedAt' IS NULL" "0"
check_sql "  → every one has its \"why compliant\" element checklist (Phase 10)" \
  "SELECT count(*) FROM ($GRES) r WHERE jsonb_array_length(coalesce(compliance_notes->'checklist', '[]'::jsonb)) = 0" "0"
check_sql "  → no compliant resource lacks a required top-level element (checklist agrees with the validator)" \
  "SELECT count(*) FROM ($GRES) r WHERE r.is_us_core_compliant AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.compliance_notes->'checklist') i WHERE (i->>'required')::boolean AND (i->>'depth')::int = 1 AND NOT (i->>'present')::boolean)" "0"
check_sql "  → each lab Observation carries its OBX-8 flag as interpretation and its OBX-7 range as referenceRange (Phase 10)" \
  "SELECT count(*) FILTER (WHERE o.abnormal_flag IS NOT NULL) > 0 AND count(*) FILTER (WHERE coalesce(o.abnormal_flag, '') <> coalesce(r.resource_json #>> '{interpretation,0,coding,0,code}', r.resource_json #>> '{interpretation,0,text}', '') OR ((o.reference_low IS NOT NULL OR o.reference_high IS NOT NULL) <> (r.resource_json ? 'referenceRange'))) = 0 FROM silver.observations o JOIN gold.fhir_resources r ON r.resource_type = 'Observation' AND r.fhir_id = 'silver-observation-' || o.id WHERE o.patient_id IN ($GP)" "t"
check_sql "  → US Core AllergyIntolerance for every AL1 allergy, all compliant" \
  "SELECT count(a.id) || '=' || count(r.id) || ' compliant=' || coalesce(bool_and(r.is_us_core_compliant), true) FROM silver.allergies a LEFT JOIN gold.fhir_resources r ON r.resource_type = 'AllergyIntolerance' AND r.fhir_id = 'silver-allergy-' || a.id WHERE a.patient_id IN ($GP)" \
  "$(psql_id "SELECT count(*) || '=' || count(*) || ' compliant=true' FROM silver.allergies WHERE patient_id IN ($GP)")"
check_sql "lineage of every Patient starts at its raw HL7 message: BRONZE (payload checksum, via Kafka) → SILVER → GOLD" \
  "SELECT count(*) > 0 AND bool_and(l.transformation_details #>> '{steps,0,stage}' = 'BRONZE' AND l.transformation_details #>> '{steps,0,input_hash}' = b.payload_checksum AND l.transformation_details #>> '{steps,0,ref}' = 'bronze.raw_messages/' || b.id AND l.transformation_details #>> '{steps,0,transformer}' LIKE '%bronze-listener-service%' AND l.transformation_details #>> '{steps,1,transformer}' LIKE 'silver-transformer-service%' AND l.transformation_details #>> '{steps,2,stage}' = 'GOLD') FROM gold.data_lineage l JOIN silver.patients p ON l.source_schema = 'silver' AND l.source_table = 'patients' AND l.source_id = p.id AND l.target_table = 'fhir_resources' JOIN bronze.raw_messages b ON b.id = p.raw_message_id WHERE p.id IN ($GP)" "t"
for _ in $(seq 1 30); do
  [[ "$(psql_id "SELECT count(*) FROM gold.outbox_events WHERE published_at IS NULL")" == "0" ]] && break; sleep 0.5
done
check_sql "gold outbox: one gold.patient.changed per silver.patient.changed of the seed, caused by it, same correlation id" \
  "SELECT count(s.id) = count(g.id) AND count(*) > 0 FROM silver.outbox_events s LEFT JOIN gold.outbox_events g ON g.headers->>'causation-id' = s.event_id::text AND g.headers->>'correlation-id' = s.headers->>'correlation-id' JOIN bronze.raw_messages b ON b.id = (s.payload->>'rawMessageId')::bigint WHERE b.payload_checksum IN ($GSHAS)" "t"
check_sql "  → keyed by master id, all relayed to Kafka" \
  "SELECT count(*) FILTER (WHERE message_key <> aggregate_id::text OR (payload->>'masterPatientId')::bigint <> aggregate_id) || '/' || count(*) FILTER (WHERE published_at IS NULL) FROM gold.outbox_events" "0/0"
# pmi-matcher: one debounced run 5 s after the burst (at most every 30 s), after the last Gold event of the seed.
LAST_GOLD="SELECT max(g.created_at) FROM gold.outbox_events g WHERE (g.payload->>'silverPatientId')::bigint IN ($GP)"
for _ in $(seq 1 90); do
  [[ "$(psql_id "SELECT count(*) FROM pmi.match_runs WHERE triggered_by LIKE 'kafka:pmi-matcher%' AND status = 'COMPLETED' AND started_at > ($LAST_GOLD)")" -ge 1 ]] && break; sleep 0.5
done
check_sql "pmi-matcher ran matching on its own after the seed's Gold events (no POST /api/pmi/match/run)" \
  "SELECT count(*) >= 1 FROM pmi.match_runs WHERE triggered_by LIKE 'kafka:pmi-matcher (%' AND status = 'COMPLETED' AND started_at > ($LAST_GOLD)" "t"
check_sql "  → debounced: far fewer runs than Gold events since the burst began" \
  "SELECT (SELECT count(*) FROM pmi.match_runs WHERE triggered_by LIKE 'kafka:%' AND started_at > '$T0') * 3 <= (SELECT count(*) FROM gold.outbox_events WHERE created_at > '$T0')" "t"
read -r IDA_SRC IDA_MRN IDA_DOB IDB_SRC IDB_MRN FZA_SRC FZA_MRN FZA_DOB FZB_SRC FZB_MRN FZB_DOB < <(python3 -c "
import json; d = json.load(open('$GMAN'))['duplicates']
i = next(x for x in d if x['kind'] == 'IDENTICAL'); f = next(x for x in d if x['kind'] == 'FUZZY')
print(i['original']['sourceSystem'], i['original']['mrn'], i['original']['dob'], i['twin']['sourceSystem'], i['twin']['mrn'],
      f['original']['sourceSystem'], f['original']['mrn'], f['original']['dob'], f['twin']['sourceSystem'], f['twin']['mrn'], f['twin']['dob'])")
sp() { psql_id "SELECT id FROM silver.patients WHERE source_system = '$1' AND source_patient_id = '$2'"; }
IA="$(sp "$IDA_SRC" "$IDA_MRN")"; IB="$(sp "$IDB_SRC" "$IDB_MRN")"; FA="$(sp "$FZA_SRC" "$FZA_MRN")"; FB="$(sp "$FZB_SRC" "$FZB_MRN")"
echo "  (identical: $IDA_SRC $IDA_MRN = $IDB_SRC $IDB_MRN, DOB $IDA_DOB; fuzzy: $FZA_SRC $FZA_MRN DOB $FZA_DOB vs $FZB_SRC $FZB_MRN DOB $FZB_DOB)"
check_sql "IDENTICAL duplicate ($IDA_SRC $IDA_MRN + $IDB_SRC $IDB_MRN) auto-merged into one MATCHED master (EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP)" \
  "SELECT (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${IA:-0}) = (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${IB:-0}) AND (SELECT match_status FROM gold.master_patients WHERE id = (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${IA:-0})) = 'MATCHED' AND EXISTS (SELECT 1 FROM pmi.match_candidates WHERE status = 'AUTO_MERGED' AND deterministic_rule = 'EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP' AND least(silver_patient_a_id, silver_patient_b_id) = least(${IA:-0}, ${IB:-0}) AND greatest(silver_patient_a_id, silver_patient_b_id) = greatest(${IA:-0}, ${IB:-0}))" "t"
check_sql "FUZZY duplicate ($FZA_SRC $FZA_MRN vs $FZB_SRC $FZB_MRN, birth day off by one, other phone) queued for review at 0.6190" \
  "SELECT string_agg(status || ' ' || score, ',') FROM pmi.match_candidates WHERE least(silver_patient_a_id, silver_patient_b_id) = least(${FA:-0}, ${FB:-0}) AND greatest(silver_patient_a_id, silver_patient_b_id) = greatest(${FA:-0}, ${FB:-0})" "PENDING 0.6190"
check_sql "  → both stay PROVISIONAL on their own masters until a data steward decides" \
  "SELECT count(DISTINCT x.master_patient_id) || ' ' || string_agg(DISTINCT m.match_status, ',') FROM gold.patient_source_xref x JOIN gold.master_patients m ON m.id = x.master_patient_id WHERE x.silver_patient_id IN (${FA:-0}, ${FB:-0})" "2 PROVISIONAL"

TOKEN="$(user_token dana)"
check_http "gateway Patient Timeline search by the identical duplicate's MRN ($IDB_MRN) — no manual build" GET "$GW/patients?q=$IDB_MRN" 200
check_json "  → one master, both registrations linked" "$TMP/last_response.json" \
  "(d['total'], [m['id'] for m in d['items']] == [$(psql_id "SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${IB:-0}")])" "(1, True)"
check_http "gateway PMI review queue lists the fuzzy pair" GET "$GW/pmi/candidates?status=PENDING&limit=200" 200
check_json "  → at 0.619" "$TMP/last_response.json" \
  "[str(c['score']) for c in (d if isinstance(d, list) else d.get('items', [])) if {c['silverPatientAId'], c['silverPatientBId']} == {${FA:-0}, ${FB:-0}}]" "['0.619']"
# FHIR Viewer filters (9c follow-up): patient, source system, origin, code; newest first by default.
check_http "FHIR Viewer: patient filter by the identical duplicate's MRN ($IDB_MRN), Patient resources" GET "$GW/fhir/resources?patient=$IDB_MRN&type=Patient" 200
check_json "  → both registrations' Patients on the merged master, built by the HL7 pipeline" "$TMP/last_response.json" \
  "(d['total'], sorted({i['sourceSystem'] for i in d['items']}), {i['origin'] for i in d['items']})" \
  "(2, ['CITY_HOSPITAL', 'NORTH_CLINIC'], {'pipeline'})"
check_http "FHIR Viewer: source + code filters find section 19's amoxicillin allergy" GET "$GW/fhir/resources?sourceSystem=SMOKE_HOSPITAL&code=Amoxicillin&type=AllergyIntolerance&size=200" 200
check_json "  → it is there, about SMOKE_HOSPITAL $SMRN" "$TMP/last_response.json" \
  "any(i['sourcePatientId'] == '$SMRN' for i in d['items']) and all(i['sourceSystem'] == 'SMOKE_HOSPITAL' for i in d['items'])" "True"
check_http "FHIR Viewer: origin=gold-seed" GET "$GW/fhir/resources?origin=gold-seed&size=200" 200
check_json "  → exactly the SEED_LOAD rows" "$TMP/last_response.json" "(d['total'], {i['origin'] for i in d['items']})" \
  "($(psql_id "SELECT count(DISTINCT target_id) FROM gold.data_lineage WHERE transformation_step = 'SEED_LOAD' AND target_table = 'fhir_resources'"), {'gold-seed'})"
check_http "FHIR Viewer: default order" GET "$GW/fhir/resources?size=25" 200
check_json "  → newest first" "$TMP/last_response.json" \
  "(lambda ts, k: [k(t) for t in ts] == sorted(map(k, ts), reverse=True))([i['updatedAt'] for i in d['items']], lambda t: (t[:19], float('0' + t[19:].rstrip('Z'))))" "True"
check_http "FHIR Viewer: unknown origin → 400" GET "$GW/fhir/resources?origin=everything" 400
# Per-type attribute filters (Phase 10): the type's own elements, values from Gold.
check_http "FHIR Viewer: Patient attribute filters" GET "$GW/fhir/attributes?type=Patient" 200
check_json "  → gender / birth date / state / city / postal code / phone, gender values from Gold" "$TMP/last_response.json" \
  "([a['key'] for a in d], 'male' in [v['value'] for v in d[0]['values']])" "(['gender', 'birthDate', 'state', 'city', 'postalCode', 'phone'], True)"
check_http "FHIR Viewer: abnormal lab results (attr.abnormal=yes)" GET "$GW/fhir/resources?type=Observation&attr.abnormal=yes&size=1" 200
check_json "  → exactly the Observations whose interpretation is H/L/HH/LL/A/AA" "$TMP/last_response.json" "d['total']" \
  "$(psql_id "SELECT count(*) FROM gold.fhir_resources WHERE resource_type = 'Observation' AND resource_json #>> '{interpretation,0,coding,0,code}' IN ('H','L','HH','LL','A','AA')")"
check_http "FHIR Viewer: female Patients born 1970–1989" GET "$GW/fhir/resources?type=Patient&attr.gender=female&attr.birthDateFrom=1970&attr.birthDateTo=1989&size=1" 200
check_json "  → the same count as the SQL" "$TMP/last_response.json" "d['total']" \
  "$(psql_id "SELECT count(*) FROM gold.fhir_resources WHERE resource_type = 'Patient' AND resource_json->>'gender' = 'female' AND left(resource_json->>'birthDate', 4) BETWEEN '1970' AND '1989'")"
check_http "FHIR Viewer: an attribute of another type → 400" GET "$GW/fhir/resources?type=Observation&attr.gender=male" 400
check_http "FHIR Viewer: low-criticality allergies" GET "$GW/fhir/resources?type=AllergyIntolerance&attr.criticality=low&size=200" 200
check_json "  → every row shows its own criticality = low (the Key attributes column)" "$TMP/last_response.json" \
  "d['total'] > 0 and all(i['attributes'].get('criticality') == 'low' for i in d['items'])" "True"
SMASTER="$(psql_id "SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${SPID:-0}")"
check_http "Patient page: allergies of section 19's patient (master #${SMASTER:-?})" GET "$GW/patients/${SMASTER:-0}" 200
check_json "  → the amoxicillin allergy with Gold criticality high and source severity SV" "$TMP/last_response.json" \
  "[(a['code'], a['criticality'], a['severity']) for a in d['allergies'] if a['code'] == '723'][:1]" "[('723', 'high', 'SV')]"
FLAGGED="$(psql_id "SELECT x.master_patient_id FROM silver.observations o JOIN gold.patient_source_xref x ON x.silver_patient_id = o.patient_id WHERE o.abnormal_flag = 'H' AND o.reference_high IS NOT NULL ORDER BY o.id DESC LIMIT 1")"
check_http "Patient timeline: a high lab result (master #${FLAGGED:-?})" GET "$GW/patients/${FLAGGED:-0}/timeline?types=OBSERVATION" 200
check_json "  → its event carries flag H and the reference range" "$TMP/last_response.json" \
  "any(e['abnormalFlag'] == 'H' and e['referenceHigh'] is not None for e in d['events'])" "True"
TOKEN=""
check_sql "section 19's hand-sent amoxicillin allergy is now a compliant US Core AllergyIntolerance (criticality high)" \
  "SELECT is_us_core_compliant || ' ' || (resource_json->>'criticality') || ' ' || (resource_json #>> '{code,coding,0,code}') FROM gold.fhir_resources WHERE resource_type = 'AllergyIntolerance' AND resource_json #>> '{patient,reference}' = 'Patient/silver-patient-${SPID:-0}'" "true high 723"

# A broken silver.patient.changed record goes straight to the dead-letter topic.
DKEY="smoke-dlt-$GSEED"
# Tagged correlation-id smoke-… so Splunk's Pipeline dashboard can tell the smoke test's dead letters from real ones.
printf 'correlation-id:%s\t%s|not json at all\n' "$DKEY" "$DKEY" | docker exec -i "$KAFKA_CONTAINER" kafka-console-producer --bootstrap-server localhost:29092 \
  --topic clinicalharmony.silver.patient.changed --property parse.key=true --property key.separator='|' --property parse.headers=true >/dev/null 2>&1
found=1
for _ in $(seq 1 6); do
  kexec kafka-console-consumer --bootstrap-server localhost:29092 --topic clinicalharmony.silver.patient.changed.dlt \
      --from-beginning --timeout-ms 10000 --property print.key=true > "$TMP/dlt.txt"
  if grep -q "^$DKEY" "$TMP/dlt.txt"; then found=0; break; fi
done
pass_if "a broken silver.patient.changed record is dead-lettered to clinicalharmony.silver.patient.changed.dlt" $found "key $DKEY"

# Logs to a file first: `docker logs | grep -q` under pipefail fails on SIGPIPE when the match comes early.
docker logs clinicalharmony-fhir-compliance-service > "$TMP/fc.log" 2>&1
docker logs clinicalharmony-patient-index-service > "$TMP/pmi.log" 2>&1
if grep -q '"event":"gold.built"' "$TMP/fc.log" && grep -q '"event":"gold.deadlettered"' "$TMP/fc.log"; then
  echo "  PASS  fhir-compliance-service logs gold.built and gold.deadlettered events"; PASS=$((PASS + 1))
else
  echo "  FAIL  no event=\"gold.built\" / \"gold.deadlettered\" line in fhir-compliance-service's log"; FAIL=$((FAIL + 1))
fi
if grep -q '"event":"pmi.run"' "$TMP/pmi.log"; then
  echo "  PASS  patient-index-service logs pmi.run events"; PASS=$((PASS + 1))
else
  echo "  FAIL  no event=\"pmi.run\" line in patient-index-service's log"; FAIL=$((FAIL + 1))
fi
for g in gold-builder pmi-matcher; do
  code="$(curl -s -o "$TMP/kafbat_$g.json" -w "%{http_code}" "$KAFBAT_URL/api/clusters/clinicalharmony/consumer-groups/$g")"
  [[ "$code" == "200" ]] && grep -q "\"groupId\":\"$g\"" "$TMP/kafbat_$g.json"; pass_if "Kafbat UI sees the $g consumer group" $? "HTTP $code"
done

section "21. Data Lineage layer browser — Silver list + patient, any-layer trace, links both ways"
# Reuses section 19's hand-sent SMOKE_HOSPITAL patient ($SMRN, Silver id $SPID: an amoxicillin allergy + order).
TOKEN="$(user_token dana)"
check_http "Silver list: exact MRN $SMRN" GET "$GW/silver/patients?q=$SMRN" 200
check_json "  → exactly that Silver patient, VALIDATED, on a Gold master" "$TMP/last_response.json" \
  "(d['total'], d['items'][0]['id'], d['items'][0]['validationStatus'], d['items'][0]['masterPatientId'] is not None)" "(1, ${SPID:-0}, 'VALIDATED', True)"
check_http "Silver list: a part of the MRN finds nothing" GET "$GW/silver/patients?q=SMK" 200
check_json "  → 0" "$TMP/last_response.json" "sum(1 for i in d['items'] if i['sourcePatientId'] == '$SMRN')" "0"
check_http "Silver patient page" GET "$GW/silver/patients/${SPID:-0}" 200
check_json "  → its Bronze messages, the allergy linked to its Gold AllergyIntolerance, its master" "$TMP/last_response.json" \
  "(len(d['bronzeMessages']) >= 2, [r['fhirResourceType'] for r in d['rows'] if r['kind'] == 'ALLERGY'], d['gold']['masterPatientId'] is not None, d['gold']['patientFhirId'])" \
  "(True, ['AllergyIntolerance'], True, 'silver-patient-${SPID:-0}')"
ALLERGY_ID="$(psql_id "SELECT id FROM silver.allergies WHERE patient_id = ${SPID:-0}")"
check_http "lineage ref silver.allergies/$ALLERGY_ID resolves to its patient" GET "$GW/silver/rows/allergies/${ALLERGY_ID:-0}" 200
check_json "  → Silver patient ${SPID:-0}" "$TMP/last_response.json" "d['silverPatientId']" "${SPID:-0}"
check_http "trace from the Silver patient (Silver patient 681 used to 404 as a 'master id')" GET "$GW/lineage?silverPatientId=${SPID:-0}" 200
check_json "  → its Gold lineage (patient + allergy + order …) and its Bronze messages" "$TMP/last_response.json" \
  "(d['target'], len(d['records']) >= 3, 'allergies' in {r['sourceTable'] for r in d['records']}, len(d['bronzeMessages']) >= 2)" "('silver.patients', True, True, True)"
ADMIT_ID="$(psql_id "SELECT id FROM bronze.raw_messages WHERE raw_payload LIKE '%|SMKA-$SEED|%'")"
check_http "trace from the allergy admit's Bronze message #$ADMIT_ID" GET "$GW/lineage?bronzeMessageId=${ADMIT_ID:-0}" 200
check_json "  → reaches the AllergyIntolerance built from it" "$TMP/last_response.json" \
  "(d['target'], any(r['sourceTable'] == 'allergies' and r['details']['target'].startswith('AllergyIntolerance/') for r in d['records']))" "('bronze.raw_messages', True)"
check_http "Bronze message page lists its Silver rows" GET "$GW/bronze/messages/${ADMIT_ID:-0}" 200
check_json "  → each with its Silver patient (for the link)" "$TMP/last_response.json" \
  "(len(d['silverRows']) >= 2, {r['silverPatientId'] for r in d['silverRows']})" "(True, {${SPID:-0}})"
check_http "Gold tab: masters carry their FHIR resource count" GET "$GW/patients?q=$SMRN" 200
check_json "  → as many as Gold has for that master" "$TMP/last_response.json" "d['items'][0]['fhirResourceCount']" \
  "$(psql_id "SELECT count(*) FROM gold.fhir_resources WHERE master_patient_id = (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${SPID:-0})")"
check_http "layer cards: /summary counts Silver patients by validation status" GET "$GW/summary" 200
check_json "  → they add up to all Silver patients" "$TMP/last_response.json" "sum(d['silverByValidation'].values()) == d['silver']['patients']" "True"
check_http "Bronze search by the patient's MRN $SMRN" GET "$GW/bronze/messages?q=$SMRN&size=50" 200
check_json "  → its admit + order (+ any repeats), each naming the patient from PID and linking Silver patient ${SPID:-0}" "$TMP/last_response.json" \
  "(d['total'] >= 2, {(i['patientName'], i['patientMrn'], i['silverPatientId']) for i in d['items']})" \
  "(True, {('ALLERGY SMOKE', '$SMRN', ${SPID:-0})})"
check_http "Bronze search by name part (allergy smoke)" GET "$GW/bronze/messages?q=allergy%20smoke&size=200" 200
check_json "  → finds them too" "$TMP/last_response.json" "any(i['patientMrn'] == '$SMRN' for i in d['items'])" "True"
check_http "the unmappable ADT (no DOB) still shows its patient, not in Silver" GET "$GW/bronze/messages/${FAILED_ID:-0}" 200
check_json "  → PID name + MRN, no Silver patient" "$TMP/last_response.json" \
  "(d['summary']['patientName'], d['summary']['patientMrn'], d['summary']['silverPatientId'])" "('PATIENT NODOB', 'SMKF$SEED', None)"
check_http "unknown Silver patient → 404" GET "$GW/silver/patients/999999999" 404
check_http "unknown validation status → 400" GET "$GW/silver/patients?validationStatus=MAYBE" 400
check_http "two trace targets at once → 400" GET "$GW/lineage?silverPatientId=1&bronzeMessageId=1" 400
TOKEN=""

section "22. REST intake over Kafka — HL7 / FHIR / claims / C-CDA → bronze-listener (Phase 9d part 1)"
RUN="$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10].upper())')"
echo "  (run id $RUN: every payload below is unique to this run, so Bronze stores it fresh)"
# rest_post NAME PATH CONTENT_TYPE BODY_FILE EXPECTED_CODE [EXTRA_HEADER] → body in $TMP/last_response.json
rest_post() {
  local name="$1" path="$2" ctype="$3" body="$4" expected="$5" extra="${6:-}"
  local args=(-s -o "$TMP/last_response.json" -D "$TMP/last_headers.txt" -w "%{http_code}" -X POST
              "$INGESTION_URL/api/ingestion/$path" -H "Content-Type: $ctype" --data-binary "@$body")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  local actual; actual="$(curl "${args[@]}")"
  if [[ "$actual" == "$expected" ]]; then echo "  PASS  $name (HTTP $actual)"; PASS=$((PASS + 1))
  else echo "  FAIL  $name (expected HTTP $expected, got $actual)"; cat "$TMP/last_response.json"; FAIL=$((FAIL + 1)); fi
}
corr_of() { python3 -c "import json; print(json.load(open('$TMP/last_response.json')).get('correlationId',''))" 2>/dev/null; }
# wait_rows CORRELATION_ID N — until bronze-listener has stored N rows for that correlation id (≤ 30 s)
wait_rows() {
  for _ in $(seq 1 60); do
    [[ "$(psql_id "SELECT count(*) FROM bronze.raw_messages WHERE correlation_id = '$1'")" -ge "$2" ]] && return 0; sleep 0.5
  done; return 1
}

for t in clinicalharmony.bronze.fhir.raw clinicalharmony.bronze.claims.raw clinicalharmony.bronze.ccda.raw \
         clinicalharmony.bronze.fhir.raw.dlt clinicalharmony.bronze.claims.raw.dlt clinicalharmony.bronze.ccda.raw.dlt; do
  parts="$(kexec kafka-topics --bootstrap-server localhost:29092 --describe --topic "$t" | grep -c 'Partition: ')"
  pass_if "topic $t declared with 3 partitions" "$([[ "$parts" == "3" ]]; echo $?)" "$parts partitions"
done

# HL7 v2 over REST: same topic as MLLP, marked REST → REST_KAFKA, mapped to Silver like an MLLP message.
printf 'MSH|^~\\&|SMOKE_APP|CITY_HOSPITAL|CH|INGEST|20261119090000||ADT^A01^ADT_A01|R9D%s|P|2.5\rEVN|A01|20261119090000\rPID|1||R9D%s^^^CITY_HOSPITAL^MR||RESTSMOKE^NINE||19700101|F\rPV1|1|O\r' "$RUN" "$RUN" > "$TMP/r_hl7.txt"
rest_post "HL7 ADT over REST → 202" hl7 text/plain "$TMP/r_hl7.txt" 202
check_json "  → names its topic, MSH-4 source and message type; no Bronze id (written later)" "$TMP/last_response.json" \
  "(d['topic'], d['sourceSystem'], d['details']['hl7MessageType'], 'rawMessageId' in d, d['correlationId'].startswith('rest-'))" \
  "('clinicalharmony.bronze.hl7v2.raw', 'CITY_HOSPITAL', 'ADT^A01', False, True)"
HCORR="$(corr_of)"
pass_if "  → correlation id echoed as X-Correlation-Id" "$([[ "$(header_of "$TMP/last_headers.txt" X-Correlation-Id)" == "$HCORR" ]]; echo $?)" "$HCORR"
wait_rows "$HCORR" 1
check_sql "  → one Bronze row, channel REST_KAFKA, from the hl7v2.raw topic" \
  "SELECT count(*) || '/' || min(intake_channel) || '/' || min(kafka_topic) FROM bronze.raw_messages WHERE correlation_id = '$HCORR'" \
  "1/REST_KAFKA/clinicalharmony.bronze.hl7v2.raw"
for _ in $(seq 1 40); do [[ "$(psql_id "SELECT processing_status FROM bronze.raw_messages WHERE correlation_id = '$HCORR'")" == "PROCESSED" ]] && break; sleep 0.5; done
check_sql "  → silver-transformer mapped it: Silver patient CITY_HOSPITAL / R9D$RUN" \
  "SELECT b.processing_status || '/' || count(p.id) FROM bronze.raw_messages b LEFT JOIN silver.patients p ON p.source_system = 'CITY_HOSPITAL' AND p.source_patient_id = 'R9D$RUN' WHERE b.correlation_id = '$HCORR' GROUP BY b.processing_status" \
  "PROCESSED/1"
REST_LINEAGE="SELECT count(*) > 0 FROM gold.data_lineage WHERE transformation_details::text LIKE '%ingestion-service REST → Kafka → bronze-listener-service (HL7V2)%' AND transformation_details::text LIKE '%' || (SELECT payload_checksum FROM bronze.raw_messages WHERE correlation_id = '$HCORR') || '%'"
for _ in $(seq 1 60); do [[ "$(psql_id "$REST_LINEAGE")" == "t" ]] && break; sleep 0.5; done
check_sql "  → gold-builder built it; its Gold lineage names the REST door" "$REST_LINEAGE" "t"
rest_post "the same HL7 bytes again → 202 (Kafka took it)" hl7 text/plain "$TMP/r_hl7.txt" 202
DCORR="$(corr_of)"
sleep 3
check_sql "  → Bronze stored it once (duplicate skipped, same source + checksum)" \
  "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = (SELECT payload_checksum FROM bronze.raw_messages WHERE correlation_id = '$HCORR')" "1"
docker logs clinicalharmony-bronze-listener-service > "$TMP/bronze.log" 2>&1
pass_if "  → bronze-listener logged bronze.duplicate for it" "$(grep -q "\"correlationId\":\"$DCORR\".*\"event\":\"bronze.duplicate\"" "$TMP/bronze.log"; echo $?)" "$DCORR"
printf 'NOT HL7 %s\r' "$RUN" > "$TMP/r_hl7_bad.txt"
rest_post "garbage over the HL7 endpoint → 422" hl7 text/plain "$TMP/r_hl7_bad.txt" 422
BCORR="$(corr_of)"; wait_rows "$BCORR" 1
check_sql "  → published anyway and QUARANTINED in Bronze" "$(printf "SELECT processing_status FROM bronze.raw_messages WHERE correlation_id = '%s'" "$BCORR")" "QUARANTINED"

# FHIR JSON: X-Source-System names the source; the caller's X-Correlation-Id is kept.
printf '{"resourceType":"Patient","id":"r9d-%s","identifier":[{"system":"urn:mrn","value":"F9D%s"}],"name":[{"family":"Restsmoke","given":["Fhir"]}],"gender":"female","birthDate":"1971-02-03"}' "$RUN" "$RUN" > "$TMP/r_fhir.json"
rest_post "FHIR Patient over REST (application/fhir+json, X-Source-System) → 202" fhir application/fhir+json "$TMP/r_fhir.json" 202 "X-Source-System: PARTNER_CLINIC"
FCORR="$(corr_of)"
curl -s -o "$TMP/last_response.json" -X POST "$INGESTION_URL/api/ingestion/fhir" -H "Content-Type: application/json" \
  -H "X-Source-System: PARTNER_CLINIC" -H "X-Correlation-Id: smoke-9d-$RUN" --data-binary "@$TMP/r_fhir.json" > /dev/null
check_json "  → the caller's correlation id is used" "$TMP/last_response.json" "(d['correlationId'], d['sourceSystem'], d['details']['resourceType'])" \
  "('smoke-9d-$RUN', 'PARTNER_CLINIC', 'Patient')"
wait_rows "$FCORR" 1
wait_status() { for _ in $(seq 1 60); do [[ "$(psql_id "SELECT string_agg(DISTINCT processing_status, ',') FROM bronze.raw_messages WHERE correlation_id = '$1'")" == "$2" ]] && return 0; sleep 0.5; done; return 1; }
wait_status "$FCORR" PROCESSED
check_sql "  → Bronze row: FHIR_JSON / JSON / REST_KAFKA from PARTNER_CLINIC, PROCESSED into Silver (9d part 2)" \
  "SELECT message_type || '/' || message_format || '/' || intake_channel || '/' || processing_status || '/' || source_system FROM bronze.raw_messages WHERE correlation_id = '$FCORR'" \
  "FHIR_JSON/JSON/REST_KAFKA/PROCESSED/PARTNER_CLINIC"
check_sql "  → Silver patient PARTNER_CLINIC / F9D$RUN (Restsmoke Fhir, 1971-02-03) from the Patient's identifier" \
  "SELECT first_name || ' ' || last_name || ' ' || date_of_birth || ' ' || gender FROM silver.patients WHERE source_system = 'PARTNER_CLINIC' AND source_patient_id = 'F9D$RUN'" \
  "Fhir Restsmoke 1971-02-03 female"
check_sql "  → the resend with another correlation id was a duplicate (no second row)" \
  "SELECT count(*) FROM bronze.raw_messages WHERE correlation_id = 'smoke-9d-$RUN'" "0"
printf '{"notFhir":"%s"}' "$RUN" > "$TMP/r_fhir_bad.json"
rest_post "JSON without resourceType → 422" fhir application/json "$TMP/r_fhir_bad.json" 422
FBCORR="$(corr_of)"; wait_rows "$FBCORR" 1
check_sql "  → QUARANTINED with the reason" \
  "SELECT processing_status || ': ' || error_message FROM bronze.raw_messages WHERE correlation_id = '$FBCORR'" \
  "QUARANTINED: JSON has no resourceType field — not a FHIR resource"

# Claims CSV: one Kafka event per file; bronze-listener splits it into one row (and one bronze.ingested) per line.
CH="claim_id,member_id,member_first_name,member_last_name,member_dob,member_gender,member_zip,service_date,icd10_code,billed_amount"
printf '%s\nC9D%s-1,M9D%s,Claire,Restsmoke,1966-06-06,F,62704,2026-11-01,E11.9,150.00\nC9D%s-2,M9D%s,Claire,Restsmoke,1966-06-06,F,62704,2026-11-02,I10,95.50\nC9D%s-3,M9D%s,Claire,Restsmoke,1966-06-06,F,62704,2026-11-03,J45.909,40.00\n' \
  "$CH" "$RUN" "$RUN" "$RUN" "$RUN" "$RUN" "$RUN" > "$TMP/r_claims.csv"
rest_post "3-line claims file over REST → 202" claims text/csv "$TMP/r_claims.csv" 202 "X-Source-System: CLAIMS_VENDOR_A"
check_json "  → reports 3 claim lines" "$TMP/last_response.json" "d['details']['claimLines']" "3"
CCORR="$(corr_of)"; wait_rows "$CCORR" 3
wait_status "$CCORR" PROCESSED
check_sql "  → 3 Bronze rows (header + one line each), all CLAIMS_CSV / CSV / REST_KAFKA, PROCESSED into Silver" \
  "SELECT count(*) || ':' || string_agg(DISTINCT message_type || '/' || message_format || '/' || intake_channel || '/' || processing_status, ',') FROM bronze.raw_messages WHERE correlation_id = '$CCORR' AND raw_payload LIKE '$CH' || chr(10) || 'C9D$RUN-_,%'" \
  "3:CLAIMS_CSV/CSV/REST_KAFKA/PROCESSED"
check_sql "  → one Silver member CLAIMS_VENDOR_A / M9D$RUN (Claire Restsmoke, 1966-06-06) with 3 silver.claims rows + 3 ICD-10 conditions" \
  "SELECT p.first_name || ' ' || p.last_name || ' ' || p.date_of_birth || ' ' || (SELECT string_agg(claim_id || '=' || icd10_code || '/' || billed_amount, ',' ORDER BY claim_id) FROM silver.claims WHERE patient_id = p.id) || ' ' || (SELECT count(*) FROM silver.conditions WHERE patient_id = p.id AND code_system = 'ICD-10') FROM silver.patients p WHERE p.source_system = 'CLAIMS_VENDOR_A' AND p.source_patient_id = 'M9D$RUN'" \
  "Claire Restsmoke 1966-06-06 C9D$RUN-1=E11.9/150.00,C9D$RUN-2=I10/95.50,C9D$RUN-3=J45.909/40.00 3"
check_sql "  → one bronze.ingested outbox event per line, numbered 1..3" \
  "SELECT string_agg(payload->>'claimLine', ',' ORDER BY (payload->>'claimLine')::int) FROM bronze.outbox_events WHERE aggregate_id IN (SELECT id FROM bronze.raw_messages WHERE correlation_id = '$CCORR')" \
  "1,2,3"
printf 'claim_id,member_id,service_date,icd10_code,billed_amount\nC9D%s-OLD,M9D%s,2026-11-01,E11.9,10.00\n' "$RUN" "$RUN" > "$TMP/r_claims_old.csv"
rest_post "a pre-9d claims line (no member columns) → 202 (well-formed CSV)" claims text/csv "$TMP/r_claims_old.csv" 202 "X-Source-System: CLAIMS_VENDOR_A"
OCORR="$(corr_of)"; wait_status "$OCORR" FAILED
check_sql "  → Bronze FAILED in Silver: the member can't be built without name / DOB / gender" \
  "SELECT processing_status || ': ' || (error_message LIKE '%no member columns%') FROM bronze.raw_messages WHERE correlation_id = '$OCORR'" "FAILED: true"
printf 'claim_id,member_id,billed_amount\nC9D%s-X,M9D%s\n' "$RUN" "$RUN" > "$TMP/r_claims_bad.csv"
rest_post "ragged claims file → 422" claims text/csv "$TMP/r_claims_bad.csv" 422
CBCORR="$(corr_of)"; wait_rows "$CBCORR" 1
check_sql "  → quarantined WHOLE (one row holding the file)" \
  "SELECT count(*) || '/' || min(processing_status) || '/' || min(error_message) FROM bronze.raw_messages WHERE correlation_id = '$CBCORR'" \
  "1/QUARANTINED/Row 1 has 2 columns, header has 3"

# C-CDA: a new Bronze format (CCDA_XML / XML).
sed -e "s/extension=\"CCD-CH-0001\"/extension=\"CCD-9D-$RUN\"/" -e "s/MRN-CCDA-1001/X9D$RUN/" "$FIXTURES/ccda_compliant.xml" > "$TMP/r_ccda.xml"
rest_post "C-CDA document over REST → 202" ccda application/xml "$TMP/r_ccda.xml" 202 "X-Source-System: NORTH_CLINIC"
check_json "  → document id + patient read" "$TMP/last_response.json" "(d['details']['documentId'], d['topic'])" \
  "('2.16.840.1.113883.19.5.99999.1^CCD-9D-$RUN', 'clinicalharmony.bronze.ccda.raw')"
XCORR="$(corr_of)"; wait_rows "$XCORR" 1; wait_status "$XCORR" PROCESSED
check_sql "  → Bronze row CCDA_XML / XML / REST_KAFKA, PROCESSED into Silver" \
  "SELECT message_type || '/' || message_format || '/' || intake_channel || '/' || processing_status FROM bronze.raw_messages WHERE correlation_id = '$XCORR'" \
  "CCDA_XML/XML/REST_KAFKA/PROCESSED"
check_sql "  → Silver patient NORTH_CLINIC / X9D$RUN (Ana Rivera, 1978-04-12): 3 problems, 3 results, 2 medications" \
  "SELECT p.first_name || ' ' || p.last_name || ' ' || p.date_of_birth || ' ' || (SELECT count(*) FROM silver.conditions WHERE patient_id = p.id) || '/' || (SELECT count(*) FROM silver.observations WHERE patient_id = p.id) || '/' || (SELECT count(*) FROM silver.medications WHERE patient_id = p.id) FROM silver.patients p WHERE p.source_system = 'NORTH_CLINIC' AND p.source_patient_id = 'X9D$RUN'" \
  "Ana Rivera 1978-04-12 3/3/2"
sed "s/Unclosed recordTarget/Unclosed $RUN/" "$FIXTURES/ccda_malformed.xml" > "$TMP/r_ccda_bad.xml"
rest_post "malformed C-CDA → 422" ccda text/xml "$TMP/r_ccda_bad.xml" 422
XBCORR="$(corr_of)"; wait_rows "$XBCORR" 1
check_sql "  → QUARANTINED as not well-formed XML" \
  "SELECT processing_status || '/' || (error_message LIKE 'Not well-formed XML%') FROM bronze.raw_messages WHERE correlation_id = '$XBCORR'" \
  "QUARANTINED/true"
printf '<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><ClinicalDocument xmlns="urn:hl7-org:v3"><title>%s &x;</title></ClinicalDocument>' "$RUN" > "$TMP/r_ccda_xxe.xml"
rest_post "C-CDA with a DOCTYPE / external entity → 422, never resolved" ccda application/xml "$TMP/r_ccda_xxe.xml" 422
check_json "  → refused for the DOCTYPE" "$TMP/last_response.json" "'DOCTYPE' in d['error']" "True"

# Requests that store nothing.
rest_post "unusable X-Source-System → 400, not published" fhir application/json "$TMP/r_fhir.json" 400 "X-Source-System: bad;source"
check_json "  → error body with a correlation id" "$TMP/last_response.json" "(d['error'], d['correlationId'].startswith('rest-'))" "('bad_header', True)"
check_http "JSON posted to the C-CDA endpoint → 415" POST "$INGESTION_URL/api/ingestion/ccda" 415 "application/json" '{"a":1}'

TOKEN="$(user_token dana)"
check_http "dashboard: Bronze list filters by the C-CDA format" GET "$GW/bronze/messages?messageType=CCDA_XML&size=50" 200
check_json "  → finds this run's document" "$TMP/last_response.json" \
  "any(i['id'] == $(psql_id "SELECT id FROM bronze.raw_messages WHERE correlation_id = '$XCORR'") for i in d['items'])" "True"
TOKEN=""

section "23. Every feed into Silver — full generator: FHIR / claims / C-CDA, cross-feed duplicates, defects (Phase 9d part 2)"
FSEED="$(( (RANDOM << 15) | RANDOM ))"
python3 "$SCRIPT_DIR/../data-generator/generate.py" --count 40 --seed "$FSEED" --out "$GEN_OUT" --host "$MLLP_HOST" \
  --port "$MLLP_PORT" --ingestion-url "$INGESTION_URL" --quiet > "$TMP/gen4.out" 2>&1
FMAN="$GEN_OUT/seed-$FSEED/manifest.json"
read -r F_HL7 F_FEEDS < <(python3 -c "import json; m=json.load(open('$FMAN')); print(m['count'], len(m['feedRecords']))")
grep -q "$F_HL7 ACK AA" "$TMP/gen4.out" && grep -q "$F_FEEDS 202 Accepted, 0 failed" "$TMP/gen4.out"
pass_if "generator (seed $FSEED) sent $F_HL7 HL7 over MLLP (all AA) + $F_FEEDS FHIR / claims / C-CDA payloads over REST (all 202)" $? "$(tail -2 "$TMP/gen4.out" | tr '\n' ' ')"
check_json "  → manifest: every feed present, cross-feed duplicates with an expected PMI outcome, planted defects" "$FMAN" \
  "(all(d['feeds'][f] > 0 for f in ('fhir','claims','ccda')), len(d['crossFeedDuplicates']) > 0, all(c['expectedPmiOutcome'] in ('AUTO_MERGED','PENDING_REVIEW') for c in d['crossFeedDuplicates']), len(d['defects']) > 0)" \
  "(True, True, True, True)"
# Bronze checksums of every feed payload: one per FHIR / C-CDA record, one per claims LINE (header + that line).
FSHAS="$(python3 - "$FMAN" "$GEN_OUT/seed-$FSEED" <<'PY'
import hashlib, json, sys
m = json.load(open(sys.argv[1])); out = []
for r in m["feedRecords"]:
    text = open(sys.argv[2] + "/" + r["file"], encoding="utf-8", newline="").read()
    if r["feed"] == "claims":
        lines = text.strip("\n").split("\n")
        out += [hashlib.sha256((lines[0] + "\n" + l).encode()).hexdigest() for l in lines[1:]]
    else:
        out.append(r["sha256"])
print(",".join("'%s'" % x for x in out))
PY
)"
F_ROWS="$(python3 -c "import json; print(sum(r['bronzeRows'] for r in json.load(open('$FMAN'))['feedRecords']))")"
for _ in $(seq 1 90); do
  [[ "$(psql_id "SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum IN ($FSHAS) AND processing_status = 'PROCESSED'")" == "$F_ROWS" ]] && break; sleep 1
done
check_sql "every feed payload landed in Bronze via REST_KAFKA and was mapped into Silver ($F_ROWS rows PROCESSED, none FAILED)" \
  "SELECT count(*) FILTER (WHERE processing_status = 'PROCESSED') || '/' || count(*) || '/' || count(*) FILTER (WHERE intake_channel = 'REST_KAFKA') FROM bronze.raw_messages WHERE payload_checksum IN ($FSHAS)" \
  "$F_ROWS/$F_ROWS/$F_ROWS"
BY_FORMAT="$(python3 - "$FMAN" <<'PY'
import json, sys
n = {}
for r in json.load(open(sys.argv[1]))["feedRecords"]:
    t = {"fhir": "FHIR_JSON", "claims": "CLAIMS_CSV", "ccda": "CCDA_XML"}[r["feed"]]
    n[t] = n.get(t, 0) + r["bronzeRows"]
print(",".join("%s=%d" % kv for kv in sorted(n.items())))
PY
)"
check_sql "  → by format: FHIR_JSON / CLAIMS_CSV / CCDA_XML rows as the manifest says ($BY_FORMAT)" \
  "SELECT string_agg(message_type || '=' || n, ',' ORDER BY message_type) FROM (SELECT message_type, count(*) n FROM bronze.raw_messages WHERE payload_checksum IN ($FSHAS) GROUP BY 1) x" \
  "$BY_FORMAT"
# Each feed's people as Silver patients (source + id from the manifest).
FPEOPLE="$(python3 -c "
import json; m = json.load(open('$FMAN'))
print(' OR '.join(\"(source_system = '%s' AND source_patient_id = '%s')\" % (r['sourceSystem'], p['id']) for r in m['feedRecords'] for p in r['people']))")"
check_sql "  → one Silver patient per person in the feeds (source system + that feed's id)" \
  "SELECT count(*) FROM silver.patients WHERE $FPEOPLE" \
  "$(python3 -c "import json; print(sum(len(r['people']) for r in json.load(open('$FMAN'))['feedRecords']))")"
check_sql "  → claims members carry silver.claims rows (one per line) and their diagnoses as conditions" \
  "SELECT count(*) = $(python3 -c "import json; print(sum(r['bronzeRows'] for r in json.load(open('$FMAN'))['feedRecords'] if r['feed']=='claims'))") FROM silver.claims c JOIN bronze.raw_messages b ON b.id = c.raw_message_id WHERE b.payload_checksum IN ($FSHAS)" "t"
# Claim lifecycle (migration 28): every generated line carries a payer + status, and Silver stores what the line says.
check_sql "  → every generated claim has a payer and a status, equal to its CSV line's claim_status" \
  "SELECT count(*) FILTER (WHERE c.payer_id IS NULL OR c.claim_status IS NULL OR c.claim_status <> split_part(split_part(b.raw_payload, E'\\n', 2), ',', 14)) FROM silver.claims c JOIN bronze.raw_messages b ON b.id = c.raw_message_id WHERE b.payload_checksum IN ($FSHAS)" "0"
CLAIM_PID="$(psql_id "SELECT c.patient_id FROM silver.claims c JOIN bronze.raw_messages b ON b.id = c.raw_message_id WHERE b.payload_checksum IN ($FSHAS) ORDER BY c.id LIMIT 1")"
SAVED_TOKEN="$TOKEN"; TOKEN="$(user_token dana)"
check_http "  → gateway: a claims member's page (Silver #${CLAIM_PID:-0})" GET "$GW/silver/patients/${CLAIM_PID:-0}" 200
check_json "    → each claim row has its payer, status and who it is waiting on (status → waitingOn as ClaimLifecycle says)" "$TMP/last_response.json" \
  "all(r['claim']['payerName'] and r['claim']['status'] == r['status'] and r['claim']['waitingOn'] == {'SUBMITTED':'PAYER','ACCEPTED':'PAYER','PENDED':'PAYER','APPEALED':'PAYER','REJECTED':'PROVIDER','VOIDED':'NOBODY'}.get(r['status'], r['claim']['waitingOn']) for r in d['rows'] if r['kind'] == 'CLAIM') and any(r['kind'] == 'CLAIM' for r in d['rows'])" "True"
TOKEN="$SAVED_TOKEN"
# Gold + PMI: wait for the build and the debounced matching run after the last event.
wait_silver_lag_zero; wait_gold_lag_zero
FP_SIDS="SELECT id FROM silver.patients WHERE $FPEOPLE"
for _ in $(seq 1 60); do
  [[ "$(psql_id "SELECT count(*) FROM ($FP_SIDS) s LEFT JOIN gold.patient_source_xref x ON x.silver_patient_id = s.id WHERE x.id IS NULL")" == "0" ]] && break; sleep 1
done
check_sql "  → every feed person reached Gold (a FHIR Patient on a master) with no manual build" \
  "SELECT count(*) FROM ($FP_SIDS) s LEFT JOIN gold.fhir_resources r ON r.resource_type = 'Patient' AND r.fhir_id = 'silver-patient-' || s.id WHERE r.master_patient_id IS NULL" "0"
LAST_FEED_GOLD="SELECT max(g.created_at) FROM gold.outbox_events g WHERE (g.payload->>'silverPatientId')::bigint IN ($FP_SIDS)"
for _ in $(seq 1 90); do
  [[ "$(psql_id "SELECT count(*) FROM pmi.match_runs WHERE status = 'COMPLETED' AND started_at > ($LAST_FEED_GOLD)")" -ge 1 ]] && break; sleep 1
done
python3 - "$FMAN" > "$TMP/xfeed.tsv" <<'PY'
import json, sys
for c in json.load(open(sys.argv[1]))["crossFeedDuplicates"]:
    o, t = c["original"], c["twin"]
    print("\t".join([c["variation"], c["feed"], c["expectedPmiOutcome"], "%.4f" % c["expectedScore"], o["sourceSystem"], o["mrn"],
                     o["dob"], t["sourceSystem"], t["id"], t["name"], t["dob"]]))
PY
while IFS=$'\t' read -r VAR FEED EXP SCORE OSRC OMRN ODOB TSRC TID TNAME TDOB; do
  A="$(sp "$OSRC" "$OMRN")"; B="$(sp "$TSRC" "$TID")"
  if [[ "$EXP" == "AUTO_MERGED" ]]; then
    check_sql "cross-feed $VAR ($FEED): $OSRC $OMRN (DOB $ODOB) = $TSRC $TID $TNAME (DOB $TDOB) → one master" \
      "SELECT (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${A:-0}) = (SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ${B:-0})" "t"
  else
    check_sql "cross-feed $VAR ($FEED): $OSRC $OMRN (DOB $ODOB) vs $TSRC $TID $TNAME (DOB $TDOB) → PMI review at $SCORE" \
      "SELECT string_agg(status || ' ' || score, ',') FROM pmi.match_candidates WHERE least(silver_patient_a_id, silver_patient_b_id) = least(${A:-0}, ${B:-0}) AND greatest(silver_patient_a_id, silver_patient_b_id) = greatest(${A:-0}, ${B:-0})" \
      "PENDING $SCORE"
  fi
done < "$TMP/xfeed.tsv"
python3 - "$FMAN" > "$TMP/defects.tsv" <<'PY'
import json, sys
for d in json.load(open(sys.argv[1]))["defects"]:
    print("\t".join([d["kind"], d["expectedRule"], d["sourceSystem"], d["patientId"], d["name"], d["dob"], d["code"] or ""]))
PY
for _ in $(seq 1 60); do
  [[ "$(psql_id "SELECT count(*) FROM silver.patients WHERE ($FPEOPLE) AND validation_status = 'PENDING_VALIDATION'")" == "0" ]] && break; sleep 1
done
while IFS=$'\t' read -r KIND RULE DSRC DID DNAME DDOB DCODE; do
  check_sql "defect $KIND: $DSRC $DID $DNAME (DOB $DDOB), code $DCODE → open $RULE finding" \
    "SELECT count(*) > 0 FROM silver.validation_reports WHERE patient_id = (SELECT id FROM silver.patients WHERE source_system = '$DSRC' AND source_patient_id = '$DID') AND rule_code = '$RULE' AND NOT is_resolved" "t"
done < "$TMP/defects.tsv"
check_sql "backlog sweeper: no Kafka-fed Bronze row left PENDING for more than 3 minutes (9d part 1 rows included)" \
  "SELECT count(*) FROM bronze.raw_messages WHERE processing_status = 'PENDING' AND intake_channel IN ('MLLP_KAFKA', 'REST_KAFKA') AND created_at < now() - interval '3 minutes'" "0"
# A fresh database (docker compose down -v) has no legacy rows at all, so the check must hold for 0 rows too.
check_sql "  → legacy REST rows (pre-9d direct writes) are never swept: none PENDING, every one FAILED with the legacy reason (migration 29)" \
  "SELECT count(*) FILTER (WHERE processing_status = 'PENDING') || ' ' || count(*) FILTER (WHERE NOT (processing_status = 'FAILED' AND error_message LIKE 'Legacy row:%')) FROM bronze.raw_messages WHERE intake_channel = 'REST'" "0 0"

# --reset: the same seed again in a new namespace → new people (nothing deduplicated, nothing deleted).
python3 "$SCRIPT_DIR/../data-generator/generate.py" --count 12 --seed "$FSEED" --feeds fhir --reset --out "$GEN_OUT" \
  --ingestion-url "$INGESTION_URL" --quiet > "$TMP/gen5.out" 2>&1
RMAN="$GEN_OUT/seed-$FSEED-ns1/manifest.json"
check_json "--reset: same seed, namespace 1 → new FHIR ids (PC1…)" "$RMAN" \
  "(d['namespace'], all(p['id'].startswith('PC1') for r in d['feedRecords'] for p in r['people']))" "(1, True)"
RPEOPLE="$(python3 -c "
import json; m = json.load(open('$RMAN'))
print(' OR '.join(\"(source_system = '%s' AND source_patient_id = '%s')\" % (r['sourceSystem'], p['id']) for r in m['feedRecords'] for p in r['people']))")"
for _ in $(seq 1 60); do [[ "$(psql_id "SELECT count(*) FROM silver.patients WHERE $RPEOPLE")" == "2" ]] && break; sleep 0.5; done
check_sql "  → its 2 Bundles became 2 NEW Silver patients" "SELECT count(*) FROM silver.patients WHERE $RPEOPLE" "2"

# --trickle: a steady rate instead of a burst.
t0=$(date +%s)
python3 "$SCRIPT_DIR/../data-generator/generate.py" --count 24 --seed "$FSEED" --feeds claims --reset --trickle --rate 2 \
  --out "$GEN_OUT" --ingestion-url "$INGESTION_URL" --quiet > "$TMP/gen6.out" 2>&1
t1=$(date +%s)
grep -q "Trickle: 4 items at 2/s" "$TMP/gen6.out" && grep -q "4 202 Accepted, 0 failed" "$TMP/gen6.out" && [[ $((t1 - t0)) -ge 1 ]]
pass_if "--trickle --rate 2: 4 claims files spread out (took $((t1 - t0)) s), all 202" $? "$(tr '\n' ' ' < "$TMP/gen6.out")"

section "24. Observability — logs + metrics in Splunk, consumer lag, dashboards, alerts firing (Phase 9e)"
SPLUNK_API="${SPLUNK_API:-https://localhost:8089}"
SPLUNK_AUTH="admin:${SPLUNK_PASSWORD:-clinicalharmony-splunk-dev}"   # DEV-ONLY (docker-compose.yml)
# spl QUERY [EARLIEST] → the first result row of a Splunk search as JSON ({} when none)
spl() {
  curl -sk -u "$SPLUNK_AUTH" "$SPLUNK_API/services/search/jobs/export" --data-urlencode "search=$1" \
    -d output_mode=json -d earliest_time="${2:--30m}" -d latest_time=now \
    | python3 -c "
import json, sys
rows = [json.loads(l)['result'] for l in sys.stdin if l.strip() and '\"result\"' in l]
print(json.dumps(rows[0] if rows else {}))"
}
spl_field() { spl "$1" "${3:--30m}" | python3 -c "import json,sys; print(json.load(sys.stdin).get('$2',''))"; }
rest_get() { curl -sk -u "$SPLUNK_AUTH" -o "$TMP/splunk_rest.json" -w "%{http_code}" "$SPLUNK_API$1?output_mode=json"; }

code="$(curl -sk -o "$TMP/hec.json" -w "%{http_code}" https://localhost:8088/services/collector/health)"
[[ "$code" == "200" ]] && grep -q "HEC is healthy" "$TMP/hec.json"; pass_if "Splunk HTTP Event Collector is healthy" $? "HTTP $code $(cat "$TMP/hec.json")"
code="$(rest_get /services/apps/local/clinicalharmony)"; pass_if "the clinicalharmony app is installed (config as code)" "$([[ "$code" == "200" ]]; echo $?)" "HTTP $code"
code="$(rest_get /services/data/indexes/clinicalharmony_metrics)"
[[ "$code" == "200" ]] && grep -q '"datatype":"metric"' "$TMP/splunk_rest.json"; pass_if "indexes clinicalharmony (events) + clinicalharmony_metrics (metric)" $? "HTTP $code"
for v in clinicalharmony_pipeline clinicalharmony_trace; do
  code="$(rest_get /servicesNS/nobody/clinicalharmony/data/ui/views/$v)"; pass_if "dashboard $v provisioned" "$([[ "$code" == "200" ]]; echo $?)" "HTTP $code"
done
for a in "Consumer lag high" "Dead letters" "Service down"; do
  code="$(rest_get "/servicesNS/nobody/clinicalharmony/saved/searches/ClinicalHarmony%20-%20${a// /%20}")"
  [[ "$code" == "200" ]] && grep -q '"is_scheduled":true' "$TMP/splunk_rest.json" && grep -q '"alert.track":true' "$TMP/splunk_rest.json"
  pass_if "alert '$a' scheduled every minute, tracked in Splunk" $? "HTTP $code"
done

# Logs: every service's JSON lines arrive (Docker splunk log driver), parsed into fields.
for _ in $(seq 1 60); do [[ "$(spl_field 'search index=clinicalharmony | stats dc(service) AS n' n -24h)" == "8" ]] && break; sleep 2; done
check_val() { [[ "$2" == "$3" ]]; pass_if "$1 ($2)" $? "expected $3, got $2"; }
check_val "logs from all 8 services in index=clinicalharmony" "$(spl_field 'search index=clinicalharmony | stats dc(service) AS n' n -24h)" "8"
check_val "  → JSON parsed: event / correlationId are fields (bronze.stored with a correlation id)" \
  "$(spl_field 'search index=clinicalharmony event="bronze.stored" correlationId=* | stats count AS n | eval ok=if(n>0,"yes","no")' ok -24h)" "yes"
# Section 22's REST HL7 message, end to end, by one correlation id (requestId is aliased to correlationId).
for _ in $(seq 1 30); do [[ "$(spl_field "search index=clinicalharmony correlationId=\"$HCORR\" | stats dc(service) AS n" n -2h)" -ge 4 ]] 2>/dev/null && break; sleep 2; done
check_val "trace by correlation id $HCORR: ingestion → bronze-listener → silver-transformer → fhir-compliance" \
  "$(spl_field "search index=clinicalharmony correlationId=\"$HCORR\" | stats values(event) AS e | eval ok=if(mvfind(e,\"^rest.published\$\")>=0 AND mvfind(e,\"^bronze.stored\$\")>=0 AND mvfind(e,\"^silver.mapped\$\")>=0 AND mvfind(e,\"^gold.built\$\")>=0,\"yes\",\"no\")" ok -2h)" "yes"
check_val "  → gold.built carries the end-to-end latency (endToEndMs)" \
  "$(spl_field "search index=clinicalharmony correlationId=\"$HCORR\" event=\"gold.built\" endToEndMs=* | stats count AS n | eval ok=if(n>0,\"yes\",\"no\")" ok -2h)" "yes"

# Metrics: Micrometer → OTLP → collector → HEC, plus the collector's own Kafka lag + health checks.
for _ in $(seq 1 60); do [[ "$(spl_field '| mstats count(clinicalharmony.bronze.messages) AS n WHERE index=clinicalharmony_metrics | eval ok=if(n>0,"yes","no")' ok -10m)" == "yes" ]] && break; sleep 2; done
check_val "pipeline counters arrive as metrics (clinicalharmony.bronze.messages)" \
  "$(spl_field '| mstats count(clinicalharmony.bronze.messages) AS n WHERE index=clinicalharmony_metrics | eval ok=if(n>0,"yes","no")' ok -10m)" "yes"
check_val "  → metrics from all 8 services (service.name)" \
  "$(spl_field '| mcatalog values(service.name) AS s WHERE index=clinicalharmony_metrics | eval n=mvcount(s)' n -10m)" "8"
check_val "consumer lag per group from the collector (kafka.consumer_group.lag_sum): bronze-writer, silver-transformer, gold-builder, pmi-matcher" \
  "$(spl_field '| mstats max(kafka.consumer_group.lag_sum) AS lag WHERE index=clinicalharmony_metrics BY group | stats values(group) AS g | eval ok=if(mvfind(g,"^bronze-writer$")>=0 AND mvfind(g,"^silver-transformer$")>=0 AND mvfind(g,"^gold-builder$")>=0 AND mvfind(g,"^pmi-matcher$")>=0,"yes","no")' ok -5m)" "yes"
check_val "health checks: all 8 /actuator/health endpoints answering 2xx" \
  "$(spl_field '| mstats max(httpcheck.status) AS up WHERE index=clinicalharmony_metrics AND http.status_class="2xx" BY http.url | where up=1 | stats count AS n' n -2m)" "8"

# Alerts actually fire. fired() NAME → number of times that alert fired in the last 15 minutes.
fired() { curl -sk -u "$SPLUNK_AUTH" "$SPLUNK_API/servicesNS/-/clinicalharmony/alerts/fired_alerts/${1// /%20}?output_mode=json&count=0" \
  | python3 -c "
import json, sys, time
try: e = json.load(sys.stdin).get('entry', [])
except Exception: e = []
print(sum(1 for x in e if time.time() - float(x['content'].get('trigger_time', 0)) < 900))"; }
wait_fired() { for _ in $(seq 1 "${2:-60}"); do [[ "$(fired "$1")" -ge 1 ]] && return 0; sleep 5; done; return 1; }
# 1. Dead letter: a broken record on clinicalharmony.bronze.hl7v2.raw → bronze-writer dead-letters it at once.
printf 'correlation-id:smoke-9e-dlt\tsmoke-9e-dlt|{not json\n' | docker exec -i "$KAFKA_CONTAINER" kafka-console-producer --bootstrap-server localhost:29092 --topic clinicalharmony.bronze.hl7v2.raw \
  --property parse.key=true --property key.separator='|' --property parse.headers=true > /dev/null
wait_fired "ClinicalHarmony - Dead letters" 48; pass_if "alert 'Dead letters' fired after a broken record was dead-lettered" $? "not fired within 4 min"
# Dashboard ↔ Splunk: the smoke test's dead letter is tagged smoke-… (the Pipeline tile leaves it out), and the
# dashboard's Bronze page can link section 22's REST message to its Splunk trace.
for _ in $(seq 1 30); do [[ "$(spl_field 'search index=clinicalharmony event="bronze.deadlettered" correlationId="smoke-9e-dlt" | stats count AS n' n -30m)" -ge 1 ]] 2>/dev/null && break; sleep 2; done
check_val "  → the smoke test's dead letter carries correlationId smoke-9e-dlt (left out of the Dead letters tile)" \
  "$(spl_field 'search index=clinicalharmony event="bronze.deadlettered" correlationId="smoke-9e-dlt" | stats count AS n | eval ok=if(n>0,"yes","no")' ok -30m)" "yes"
HBRONZE="$(psql_id "SELECT id FROM bronze.raw_messages WHERE correlation_id = '$HCORR' ORDER BY id LIMIT 1")"
SAVED_TOKEN="$TOKEN"; TOKEN="$(user_token dana)"
check_http "gateway: Bronze message #${HBRONZE:-0} (section 22's REST HL7)" GET "$GW/bronze/messages/${HBRONZE:-0}" 200
check_json "  → carries its correlation id and intake channel (the UI's \"Open trace in Splunk\")" "$TMP/last_response.json" \
  "(d['correlationId'], d['intakeChannel'])" "('$HCORR', 'REST_KAFKA')"
TOKEN="$SAVED_TOKEN"
code="$(curl -s -o "$TMP/gw_config.json" -w "%{http_code}" "$GW/config")"
check_json "  → GET /config names Splunk's Trace dashboard (HTTP $code)" "$TMP/gw_config.json" \
  "d['observability']['splunkTraceUrl']" "http://localhost:8000/en-US/app/clinicalharmony/clinicalharmony_trace"

# 2 + 3. Stop bronze-writer's service, then send 60 HL7 messages: service down + lag > 50 for 3 minutes.
docker stop clinicalharmony-bronze-listener-service > /dev/null
python3 "$SCRIPT_DIR/../data-generator/generate.py" --count 60 --seed "$(( (RANDOM << 15) | RANDOM ))" --feeds hl7 --malformed-rate 0 \
  --out "$GEN_OUT" --host "$MLLP_HOST" --port "$MLLP_PORT" --quiet > "$TMP/gen7.out" 2>&1
wait_fired "ClinicalHarmony - Service down" 48; pass_if "alert 'Service down' fired with bronze-listener-service stopped" $? "not fired within 4 min"
wait_fired "ClinicalHarmony - Consumer lag high" 72; pass_if "alert 'Consumer lag high' fired (bronze-writer ≥ 60 behind for 3 min)" $? "not fired within 6 min"
docker start clinicalharmony-bronze-listener-service > /dev/null
for _ in $(seq 1 90); do [[ "$(group_lag bronze-writer clinicalharmony.bronze.hl7v2.raw)" == "0" ]] && break; sleep 2; done
pass_if "  → bronze-listener back: it caught up (lag 0), nothing lost" "$([[ "$(group_lag bronze-writer clinicalharmony.bronze.hl7v2.raw)" == "0" ]]; echo $?)" "lag $(group_lag bronze-writer clinicalharmony.bronze.hl7v2.raw)"
check_val "  → the dashboard's alert panel query lists all three (fired_alerts REST)" \
  "$(spl_field '| rest /servicesNS/-/clinicalharmony/alerts/fired_alerts/- splunk_server=local | stats dc(savedsearch_name) AS n' n -1h)" "3"

echo
echo "=== Summary: $PASS passed, $FAIL failed ==="
[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
