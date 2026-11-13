# data-generator

**Phase 9** — Python generator of synthetic hospital data, used to feed the pipeline the way real
source systems would.

- **9a:** 100 seeded HL7 v2 messages (ADT + ORU, ~10% deliberately malformed), sent over MLLP to
  ingestion-service, which publishes them to Kafka for the Bronze listener.
- **9d:** all feeds (HL7 v2, FHIR JSON, claims CSV, C-CDA), cross-feed duplicate patients for the
  patient index, deliberate defects for validation, `--seed` / `--reset` / `--trickle` options.

See the [roadmap](../README.md#roadmap). Not yet implemented.
