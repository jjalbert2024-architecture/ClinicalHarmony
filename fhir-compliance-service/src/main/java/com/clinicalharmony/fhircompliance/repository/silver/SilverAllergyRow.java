package com.clinicalharmony.fhircompliance.repository.silver;

/**
 * One row of silver.allergies (Phase 9b, from HL7 AL1).
 *
 * @param codeSystem  RXNORM, SNOMED-CT or LOCAL (table CHECK)
 * @param allergyType HL7 table 0127: DA drug, FA food, MA miscellaneous, MC miscellaneous contraindication,
 *                    EA environmental, AA animal, PA plant, LA pollen — or null
 * @param severity    HL7 table 0128: SV severe, MO moderate, MI mild, U unknown — or null
 */
public record SilverAllergyRow(
        long id,
        long patientId,
        String codeSystem,
        String code,
        String display,
        String allergyType,
        String severity,
        String reaction
) {
}
