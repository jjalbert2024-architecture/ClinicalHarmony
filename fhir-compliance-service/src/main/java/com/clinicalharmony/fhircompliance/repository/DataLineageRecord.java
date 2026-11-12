package com.clinicalharmony.fhircompliance.repository;

/** A gold.data_lineage row to upsert. {@code transformationDetails} is JSON text ({"steps": [...]}). */
public record DataLineageRecord(
        String sourceSchema,
        String sourceTable,
        Long sourceId,
        String targetSchema,
        String targetTable,
        long targetId,
        String transformationStep,
        String transformationDetails
) {
}
