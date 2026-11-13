package com.clinicalharmony.gateway.downstream;

/**
 * The services behind the gateway. {@code key} is the {@code gateway.services.<key>} config entry;
 * {@code secured} services (Bearer-protected since Phase 5b slice 4 / Phase 6) get the gateway's
 * own client_credentials token on every call — the others have no auth yet.
 */
public enum Downstream {

    INGESTION("ingestion", "ingestion-service", false),
    ONTOLOGY("ontology", "ontology-service", false),
    RULES_ENGINE("rules-engine", "rules-engine-service", false),
    FHIR_COMPLIANCE("fhir-compliance", "fhir-compliance-service", true),
    PATIENT_INDEX("patient-index", "patient-index-service", true);

    private final String key;
    private final String serviceName;
    private final boolean secured;

    Downstream(String key, String serviceName, boolean secured) {
        this.key = key;
        this.serviceName = serviceName;
        this.secured = secured;
    }

    public String key() {
        return key;
    }

    public String serviceName() {
        return serviceName;
    }

    public boolean secured() {
        return secured;
    }
}
