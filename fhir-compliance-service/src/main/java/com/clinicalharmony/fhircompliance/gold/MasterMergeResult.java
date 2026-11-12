package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.MasterPatientSnapshot;

import java.util.List;

public record MasterMergeResult(long mergeId, MasterPatientSnapshot survivor, MasterPatientSnapshot absorbed,
                                List<Long> movedSilverPatientIds, List<Long> movedFhirResourceIds) {
}
