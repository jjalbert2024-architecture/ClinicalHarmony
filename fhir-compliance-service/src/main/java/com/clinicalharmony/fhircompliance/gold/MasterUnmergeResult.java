package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.MasterPatientSnapshot;

import java.util.List;

public record MasterUnmergeResult(long mergeId, MasterPatientSnapshot survivor, MasterPatientSnapshot restored,
                                  List<Long> restoredSilverPatientIds, int restoredFhirResourceCount) {
}
