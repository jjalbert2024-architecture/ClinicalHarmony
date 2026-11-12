package com.clinicalharmony.fhircompliance.config;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.hapi.converters.canonical.VersionCanonicalizer;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.common.hapi.validation.validator.VersionSpecificWorkerContextWrapper;
import org.hl7.fhir.r5.model.Resource;

import java.util.List;

/**
 * {@link FhirInstanceValidator} with one gap in HAPI FHIR 8.2.0 filled.
 *
 * <p>The core validator's MeasureValidator resolves a <em>version-pinned</em> {@code
 * MeasureReport.measure} ({@code url|version}) via {@code IWorkerContext.fetchResourcesByUrl},
 * which HAPI's {@link VersionSpecificWorkerContextWrapper} doesn't implement — it throws
 * {@code HAPI-2509 UnsupportedOperationException}, aborting validation of the whole resource.
 * Da Vinci DEQM's invariant {@code deqm-0} <em>requires</em> that version pin, so without this
 * no DEQM-conformant MeasureReport could be validated at all (and /api/fhir/validate would 500 on
 * one).
 *
 * <p>The override resolves the URL through the normal single-resource lookup and returns zero or
 * one match. No Measure resources are bundled, so in practice it returns an empty list and the
 * validator reports the measure as unresolvable — at warning/information level, like any other
 * canonical we deliberately don't ship — and carries on validating the report against its profile.
 */
class VersionedCanonicalFhirInstanceValidator extends FhirInstanceValidator {

    private VersionSpecificWorkerContextWrapper workerContext;

    VersionedCanonicalFhirInstanceValidator(IValidationSupport validationSupport) {
        super(validationSupport);
    }

    @Override
    protected synchronized VersionSpecificWorkerContextWrapper provideWorkerContext() {
        if (workerContext == null) {
            IValidationSupport support = getValidationSupport();
            workerContext = new ListingWorkerContext(new ValidationSupportContext(support),
                    new VersionCanonicalizer(support.getFhirContext()));
        }
        return workerContext;
    }

    static final class ListingWorkerContext extends VersionSpecificWorkerContextWrapper {

        ListingWorkerContext(ValidationSupportContext context, VersionCanonicalizer canonicalizer) {
            super(context, canonicalizer);
        }

        @Override
        public <T extends Resource> List<T> fetchResourcesByUrl(Class<T> type, String url) {
            T match = fetchResource(type, url);
            return match == null ? List.of() : List.of(match);
        }
    }
}
