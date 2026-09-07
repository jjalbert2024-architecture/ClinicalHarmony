#!/usr/bin/env bash
# End-to-end smoke test for ingestion-service, ontology-service, and rules-engine-service.
# Assumes the stack is already up: `docker-compose up -d` from the repo root.
# Usage: ./scripts/smoke-test.sh
set -uo pipefail

INGESTION_URL="${INGESTION_URL:-http://localhost:8081}"
ONTOLOGY_URL="${ONTOLOGY_URL:-http://localhost:8082}"
RULES_URL="${RULES_URL:-http://localhost:8083}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/fixtures"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

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
  actual="$(curl "${args[@]}")"
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

section "0. Health checks"
for pair in "ingestion:$INGESTION_URL" "ontology:$ONTOLOGY_URL" "rules-engine:$RULES_URL"; do
  name="${pair%%:*}"; url="${pair#*:}"
  check_http "$name-service is up" GET "$url/actuator/health" 200
done

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

echo
echo "=== Summary: $PASS passed, $FAIL failed ==="
[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
