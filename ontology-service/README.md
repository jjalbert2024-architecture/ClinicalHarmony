# ontology-service

Spring Boot ontology validation engine. Validates diagnosis codes (ICD-10, SNOMED CT), lab
codes (LOINC), and medication codes (RxNorm) against the reference tables in the `ontology`
schema, records every failure in `silver.validation_reports`, and answers cross-terminology
mapping queries (e.g. SNOMED CT -> ICD-10).

## Endpoints (port 8082)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/ontology/validate` | Validate a canonical patient record (conditions, observations, medications); persists a `silver.validation_reports` row for every failure |
| `GET` | `/api/ontology/codes/icd10/{code}` | Look up a single ICD-10 code — `200` if found, `404` if not |
| `GET` | `/api/ontology/codes/loinc/{code}` | Look up a single LOINC code — `200`/`404` |
| `GET` | `/api/ontology/map?source=SNOMED&code={code}&target=ICD10` | Cross-terminology lookup via `ontology.concept_mappings` |

`POST /api/ontology/validate` request shape — each item carries its own caller-assigned `id`
(used as `entity_id` in any resulting validation report, since no Silver-layer parser exists
yet to assign real persisted row ids):

```json
{
  "patientId": 555,
  "conditions": [
    {"id": 9001, "codeSystem": "ICD-10", "code": "E11.9"},
    {"id": 9002, "codeSystem": "SNOMED-CT", "code": "44054006"}
  ],
  "observations": [{"id": 9101, "code": "2093-3"}],
  "medications": [{"id": 9201, "rxnormCode": "6809"}]
}
```

## Design notes

- **4 validators** (`Icd10Validator`, `LoincValidator`, `RxNormValidator`, `SnomedValidator`), each a
  plain `JdbcTemplate` lookup against its `ontology.*` table — no ORM, matching `ingestion-service`'s
  convention. Null/blank codes are rejected before ever reaching the database.
- **`OntologyService`** dispatches each condition to `Icd10Validator` or `SnomedValidator` based on
  its `codeSystem`, observations always to `LoincValidator`, medications always to `RxNormValidator`.
- **`ValidationReportBuilder`** only persists *failed* validations — passing codes don't need an
  audit row. Every finding cites the `ontology.clinical_rules` rule it violated (`CODE-001` ICD-10,
  `CODE-002` LOINC, `CODE-003` RxNorm, `CODE-004` SNOMED CT).
- **`ConceptMappingService`** normalizes common system aliases (`ICD10` and `ICD-10` both work) before
  querying `ontology.concept_mappings`.
- **Spring profiles**: `local` (datasource points at `localhost:5432`, for `mvn spring-boot:run` on the
  host) and `docker` (resolves the datasource host via `DB_HOST`, set by docker-compose). Active
  profile controlled by `SPRING_PROFILES_ACTIVE`.
- **MDC logging**: `MdcLoggingFilter` stamps a short correlation id into every request's log lines
  (see the `%X{requestId}` pattern in `application.yml`).

## Schema additions this service required

Phase 1 only created `ontology.icd10_codes`, `loinc_codes`, `rxnorm_codes`, and `clinical_rules`.
This phase added, via `docs/db-init/08` through `12`:
- `ontology.snomed_codes` (new table) + 15 seeded concepts
- `ontology.concept_mappings` (new table) + 20 seeded SNOMED CT -> ICD-10 mappings
- `CODE-004` rule in `ontology.clinical_rules` (SNOMED CT validation failures)
- `patient_id`, `bad_value`, `suggested_value` columns on `silver.validation_reports` (originally
  only had a free-text `message`; the Validation Report dashboard view will want these as queryable
  fields, not parsed out of text)
