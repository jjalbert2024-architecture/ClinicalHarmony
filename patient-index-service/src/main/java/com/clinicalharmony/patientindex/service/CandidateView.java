package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.MatchCandidate;

/** A review item with both Silver records side by side, as they are now. */
public record CandidateView(MatchCandidate candidate, PatientRecord recordA, PatientRecord recordB) {
}
