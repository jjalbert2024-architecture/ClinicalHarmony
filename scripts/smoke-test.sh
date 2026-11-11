#!/usr/bin/env bash
# End-to-end smoke test for ingestion-service, ontology-service, rules-engine-service, and
# fhir-compliance-service.
# Assumes the stack is already up: `docker-compose up -d` from the repo root.
# Usage: ./scripts/smoke-test.sh
set -uo pipefail

INGESTION_URL="${INGESTION_URL:-http://localhost:8081}"
ONTOLOGY_URL="${ONTOLOGY_URL:-http://localhost:8082}"
RULES_URL="${RULES_URL:-http://localhost:8083}"
FHIR_COMPLIANCE_URL="${FHIR_COMPLIANCE_URL:-http://localhost:8084}"

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
for pair in "ingestion:$INGESTION_URL" "ontology:$ONTOLOGY_URL" "rules-engine:$RULES_URL" "fhir-compliance:$FHIR_COMPLIANCE_URL"; do
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

section "7. fhir-compliance-service — US Core structural validation"
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/validate" -H "Content-Type: application/json" \
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
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/generate" -H "Content-Type: application/json" \
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
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/validate" -H "Content-Type: application/json" \
  --data-binary "@$TMP/fhir_roundtrip_request.json" > "$TMP/fhir_roundtrip.json"
check_json "every generated resource round-trips as US Core valid" "$TMP/fhir_roundtrip.json" \
  "d['allValid']" "True"
check_http "GET /api/fhir-compliance/rules (7 active USCORE rules)" GET "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules" 200
check_json "exactly 7 active USCORE rules" "$TMP/last_response.json" "len(d)" "7"
check_http "GET unknown rule returns 404" GET "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/NOT_A_REAL_RULE" 404

# Toggle off, confirm it's skipped (not failed) from validation, then always restore it.
curl -s -X PUT "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle" > /dev/null
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir-compliance/validate" -H "Content-Type: application/json" \
  -d '{"patientId": 903, "fhirResources": [{"resourceType": "Patient", "id": "p-incomplete"}]}' > "$TMP/fhir_toggled_off.json"
check_json "toggled-off Patient rule is skipped, not failed" "$TMP/fhir_toggled_off.json" \
  "d['allValid']" "True"
curl -s -X PUT "$FHIR_COMPLIANCE_URL/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle" > "$TMP/fhir_restored.json"
check_json "USCORE_PATIENT_COMPLETENESS restored to active" "$TMP/fhir_restored.json" "d['active']" "True"

section "9. fhir-compliance-service — HL7 validator vs US Core 6.1.0 (Phase 5b)"
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","name":[{"family":""}]}' > "$TMP/hl7_validate_bad.json"
check_json "bare Patient is checked against US Core and fails" "$TMP/hl7_validate_bad.json" "d['compliant']" "False"
check_json "missing gender reported at its FHIRPath with a suggested fix" "$TMP/hl7_validate_bad.json" \
  "[v['suggestedFix'] is not None for v in d['violations'] if v['location']=='Patient.gender']" "[True]"
check_json "empty string reported at Patient.name[0].family" "$TMP/hl7_validate_bad.json" \
  "any(v['location']=='Patient.name[0].family' and v['severity']=='ERROR' for v in d['violations'])" "True"
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" -H "Content-Type: application/fhir+json" \
  -d '{"resourceType":"Patient","identifier":[{"system":"urn:x","value":"1"}],"name":[{"family":"Doe"}],"gender":"female"}' \
  > "$TMP/hl7_validate_good.json"
check_json "complete Patient is US Core compliant" "$TMP/hl7_validate_good.json" "d['compliant']" "True"
check_http "non-FHIR payload rejected (400)" POST "$FHIR_COMPLIANCE_URL/api/fhir/validate" 400 "application/json" '{"foo":1}'

section "10. fhir-compliance-service — Silver → US Core FHIR → Gold (Phase 5b)"
# Needs docs/db-init/16 + 17 applied (compliance_notes column + seed Silver patients).
curl -s -X POST "$FHIR_COMPLIANCE_URL/api/fhir/build" > "$TMP/silver_build.json"
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
curl -s -X POST "$CCDA_URL" -H "Content-Type: application/xml" --data-binary "@$FIXTURES/ccda_compliant.xml" > "$TMP/ccda_good_again.json"
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
echoed="$(curl -s -o /dev/null -D - -X POST "$CCDA_URL" -H "Content-Type: application/xml" -H "X-Correlation-Id: smoke-ccda-1" \
  --data-binary "@$FIXTURES/ccda_compliant.xml" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
if [[ "$echoed" == "smoke-ccda-1" ]]; then
  echo "  PASS  caller's X-Correlation-Id echoed back ($echoed)"; PASS=$((PASS + 1))
else
  echo "  FAIL  caller's X-Correlation-Id echoed back (got '$echoed')"; FAIL=$((FAIL + 1))
fi

echo
echo "=== Summary: $PASS passed, $FAIL failed ==="
[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
