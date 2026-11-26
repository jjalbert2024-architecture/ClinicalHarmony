# Try it: hands-on examples

Run these from the repository root with the stack up (`docker-compose up -d`). The
[README](../README.md#try-it) has the short version.

**Generate synthetic data and send it through the pipeline** (host `python3`, nothing to install):

```bash
python3 data-generator/generate.py                  # seed 42: HL7 over MLLP + FHIR, claims, C-CDA over REST
python3 data-generator/generate.py --feeds hl7      # HL7 only
python3 data-generator/generate.py --help           # --count, --seed, --reset, --trickle …
```

Then watch `clinicalharmony.bronze.hl7v2.raw` and the `bronze-writer` group in Kafbat UI
(`localhost:8090`), and the rows in the dashboard's **Data Lineage → Bronze**. Running the same seed
again re-sends identical bytes: all ACKed, nothing stored twice.

**Ingest over REST.** Every REST payload is published to Kafka (like MLLP) and `bronze-listener-service`
stores it in `bronze.raw_messages`. `202 Accepted` = passed the syntax check and Kafka confirmed it; the body has the
`correlationId` (also `X-Correlation-Id`) to find the row by, not a Bronze id. `422` = failed the check — published
anyway, so Bronze keeps it as `QUARANTINED`. `503` + `Retry-After` = Kafka didn't confirm, nothing stored.

```bash
# HL7 v2 ADT^A01
curl -i -X POST http://localhost:8081/api/ingestion/hl7 -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260703101500||ADT^A01^ADT_A01|MSG00001|P|2.5\rPID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F\r'

# FHIR JSON
curl -i -X POST http://localhost:8081/api/ingestion/fhir -H "Content-Type: application/json" \
  -H "X-Source-System: PARTNER_CLINIC" \
  -d '{"resourceType":"Patient","id":"pat-1","name":[{"family":"Doe","given":["Jane"]}]}'

# Claims CSV (one Kafka event per file; one Bronze row per claim line, or the whole file quarantined)
curl -i -X POST http://localhost:8081/api/ingestion/claims -H "Content-Type: text/csv" \
  -H "X-Source-System: CLAIMS_VENDOR_A" \
  --data-binary $'claim_id,member_id,member_first_name,member_last_name,member_dob,member_gender,service_date,icd10_code,billed_amount\nCLM-001,MBR-1001,JANE,DOE,1980-05-15,F,2026-06-01,E11.9,150.00\n'

# C-CDA document (Bronze message_type CCDA_XML)
curl -i -X POST http://localhost:8081/api/ingestion/ccda -H "Content-Type: application/xml" \
  -H "X-Source-System: NORTH_CLINIC" --data-binary @scripts/fixtures/ccda_compliant.xml
```

**Validate codes and map across terminologies:**

```bash
curl http://localhost:8082/api/ontology/codes/icd10/E11.9
curl "http://localhost:8082/api/ontology/map?source=SNOMED&code=44054006&target=ICD10"
```

**Evaluate clinical rules.** The fixture is a deliberately inconsistent record that trips all 7
rules: among other things, a pregnant male patient, a diagnosis dated in the future, an
out-of-range lab, and a medication the patient is allergic to.

```bash
curl -s -X POST http://localhost:8083/api/rules/evaluate -H "Content-Type: application/json" \
  --data-binary @scripts/fixtures/rules_evaluate_request.json
```

**Get a SMART backend-services token** (client credentials against Keycloak; the token lives 5 minutes).
`/fhir/**`, the Gold builders and C-CDA conversion need one; the validators and discovery don't:

```bash
curl -s http://localhost:8084/.well-known/smart-configuration
TOKEN=$(curl -s -u clinicalharmony-app:clinicalharmony-app-secret -d grant_type=client_credentials \
  --data-urlencode "scope=system/*.rs clinicalharmony/pipeline.write" \
  http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
```

**Validate a FHIR resource against US Core 6.1.0.** Each violation comes back with its FHIRPath location and a suggested fix:

```bash
curl -s -X POST http://localhost:8084/api/fhir/validate -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","name":[{"family":""}]}'
```

**Convert a C-CDA document to a US Core FHIR Bundle:**

```bash
curl -s -X POST http://localhost:8084/api/fhir/convert/ccda -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/xml" \
  --data-binary @scripts/fixtures/ccda_compliant.xml
```

**Build Gold FHIR resources from the Silver demo patients.** One is deliberately non-compliant, to show how findings are recorded:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/fhir/build
```

Then look at what landed, and how each Gold resource traces back through Silver:

```bash
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony -c \
  "SELECT resource_type, fhir_id, is_us_core_compliant, master_patient_id FROM gold.fhir_resources ORDER BY id DESC LIMIT 10;"
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony -c \
  "SELECT source_table, source_id, transformation_step, jsonb_pretty(transformation_details) FROM gold.data_lineage ORDER BY id DESC LIMIT 1;"
```

**Query the CMS-0057-F payer API** (served from Gold; errors, including 401/403, are FHIR OperationOutcomes):

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8084/fhir/r4/Coverage?patient=silver-patient-1"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8084/fhir/r4/MedicationKnowledge?code=http://www.nlm.nih.gov/research/umls/rxnorm|861007"
curl -si -H "Authorization: Bearer $TOKEN" http://localhost:8084/fhir/r4/PractitionerRole/cms-practitionerrole-2 | grep -i x-profile
```

**Match patients across sources** (patient-index-service; `docs/db-init/21` seeds the same people
arriving from different feeds). Build first so every Silver patient has a master, then run:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/fhir/build > /dev/null
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/pmi/match/run
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/pmi/candidates   # the review queue
```

The run auto-merges the claims-feed copy of Maria Garcia (deterministic) and "Jim" Wilson (0.91), and
queues two for a human: a day/month-swapped birth date (0.65) and Maria's opposite-sex twin (0.71 —
the gender conflict keeps him out of auto-merge). Accept, reject or undo with
`POST /api/pmi/candidates/{id}/accept|reject` and `POST /api/pmi/merges/{id}/unmerge`.

**Use the dashboard API** (api-gateway). Sign in as a dashboard user — `dana` is a data steward,
`vic` a viewer (DEV-ONLY password grant; the real UI uses Auth Code + PKCE) — then everything the
five dashboard views show comes from `/api/v1`:

```bash
USER_TOKEN=$(curl -s -d grant_type=password -d client_id=clinicalharmony-dashboard-dev -d username=dana \
  -d password=dana-dev-password http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/summary
curl -s -H "Authorization: Bearer $USER_TOKEN" "http://localhost:8080/api/v1/patients?q=garcia"
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/patients/1/timeline
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/ontology/coverage
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/services/health
```

Errors are RFC 7807 problems with a correlation id; a service that's down is a 503 naming it.

**Use the dashboard.** Open <http://localhost:3000> and sign in as `dana` / `dana-dev-password`
(data steward) or `vic` / `vic-dev-password` (viewer) — DEV-ONLY users from the realm import.
[`dashboard-ui/CHECKLIST.md`](../dashboard-ui/CHECKLIST.md) is a 12-step click-through of all five views.
