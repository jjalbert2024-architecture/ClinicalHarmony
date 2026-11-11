package com.clinicalharmony.fhircompliance.service.ccda;

/**
 * Something the transformer couldn't carry over from the C-CDA, or a section it didn't find.
 * Distinct from US Core violations: those describe the FHIR output; these describe what was lost
 * or skipped getting there.
 *
 * @param section the C-CDA section (e.g. {@code "Results (30954-2)"}) or {@code "Document"}
 * @param message plain-English description, including the entry's position where relevant
 */
public record ConversionNote(String section, String message) {
}
