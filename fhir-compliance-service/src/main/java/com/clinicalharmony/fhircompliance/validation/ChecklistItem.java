package com.clinicalharmony.fhircompliance.validation;

/**
 * One line of the "why compliant" checklist: an element the profile requires (min ≥ 1), marks
 * must-support or lists as a USCDI requirement, and whether the resource has it.
 *
 * <p>Must-support doesn't mean "must be present": a sender must include it when it has the data, so
 * an absent must-support element is allowed. An absent required element is what the validator
 * reports as an error.
 *
 * @param path        FHIRPath-like element path, e.g. {@code Patient.name.family}; slices as
 *                    {@code Patient.extension:race}
 * @param label       short name for display: the element name, or the slice name
 * @param depth       1 = a top-level element of the resource, 2 = a child of one
 * @param required    min ≥ 1 in the profile (for depth 2: required whenever its parent is present)
 * @param mustSupport flagged must-support in the profile
 * @param uscdi       flagged an "Additional USCDI Requirement" (US Core 6.x {@code uscdi-requirement}
 *                    extension): systems must support it for ONC certification even where it isn't
 *                    must-support, e.g. Patient race / ethnicity
 * @param present     the resource has the element (a primitive's {@code _element}, e.g. a
 *                    data-absent-reason extension, counts as present)
 */
public record ChecklistItem(
        String path,
        String label,
        int depth,
        boolean required,
        boolean mustSupport,
        boolean uscdi,
        boolean present
) {
}
