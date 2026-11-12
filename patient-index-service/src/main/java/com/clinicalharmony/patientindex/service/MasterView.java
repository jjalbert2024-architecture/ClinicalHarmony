package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.FhirResourceSummary;
import com.clinicalharmony.patientindex.repository.MasterRow;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MergeRow;

import java.util.List;

/**
 * One master patient with everything that hangs off it.
 *
 * @param mergedInto the live merge that absorbed this master (null unless it's MERGED)
 * @param sources    the Silver patients (source records) under it
 * @param merges     merges it took part in, as survivor or absorbed, including undone ones
 */
public record MasterView(MasterRow master, MergeRow mergedInto, List<PatientRecord> sources,
                         List<FhirResourceSummary> fhirResources, List<MergeRow> merges,
                         List<MatchCandidate> pendingCandidates) {
}
