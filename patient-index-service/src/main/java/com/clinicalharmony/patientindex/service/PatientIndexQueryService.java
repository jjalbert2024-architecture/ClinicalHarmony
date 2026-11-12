package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.matching.MatchScore;
import com.clinicalharmony.patientindex.matching.PatientMatcher;
import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.MasterRow;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MatchCandidateRepository;
import com.clinicalharmony.patientindex.repository.MatchRun;
import com.clinicalharmony.patientindex.repository.MatchRunRepository;
import com.clinicalharmony.patientindex.repository.MergeRow;
import com.clinicalharmony.patientindex.repository.PmiReadRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Read side of the PMI: masters with their sources, review items, runs, ad-hoc pair scores. */
@Service
public class PatientIndexQueryService {

    private final PmiReadRepository reads;
    private final MatchCandidateRepository candidates;
    private final MatchRunRepository runs;
    private final MatchSettingsLoader settingsLoader;
    private final PatientMatcher matcher;

    public PatientIndexQueryService(PmiReadRepository reads, MatchCandidateRepository candidates, MatchRunRepository runs,
                                    MatchSettingsLoader settingsLoader, PatientMatcher matcher) {
        this.reads = reads;
        this.candidates = candidates;
        this.runs = runs;
        this.settingsLoader = settingsLoader;
        this.matcher = matcher;
    }

    public MasterView master(long masterId) {
        MasterRow master = reads.findMaster(masterId)
                .orElseThrow(() -> new NotFoundException("No master patient with id " + masterId));
        List<PatientRecord> sources = reads.sourcesOfMaster(masterId);
        List<MergeRow> merges = reads.mergesInvolving(masterId);
        MergeRow mergedInto = merges.stream()
                .filter(m -> m.live() && m.absorbedMasterId() == masterId).findFirst().orElse(null);
        List<MatchCandidate> pending = candidates.pendingInvolving(sources.stream().map(PatientRecord::silverPatientId).toList());
        return new MasterView(master, mergedInto, sources, reads.fhirResourcesOfMaster(masterId), merges, pending);
    }

    public List<MasterRow> masters(String status, int limit) {
        return reads.listMasters(status, limit);
    }

    public CandidateView candidate(long candidateId) {
        MatchCandidate c = candidates.find(candidateId)
                .orElseThrow(() -> new NotFoundException("No match candidate with id " + candidateId));
        return new CandidateView(c, reads.findPatient(c.silverPatientAId()).orElse(null),
                reads.findPatient(c.silverPatientBId()).orElse(null));
    }

    public List<MatchCandidate> candidates(String status, int limit) {
        return candidates.list(status, limit);
    }

    public MatchRun run(long runId) {
        return runs.find(runId).orElseThrow(() -> new NotFoundException("No matching run with id " + runId));
    }

    public List<MatchRun> runs(int limit) {
        return runs.recent(limit);
    }

    /** Explain how two Silver patients would score right now (whether or not they have masters). */
    public MatchScore score(long silverPatientA, long silverPatientB) {
        if (silverPatientA == silverPatientB) {
            throw new ConflictException("Pick two different Silver patients");
        }
        PatientRecord a = reads.findPatient(silverPatientA)
                .orElseThrow(() -> new NotFoundException("No silver.patients row with id " + silverPatientA));
        PatientRecord b = reads.findPatient(silverPatientB)
                .orElseThrow(() -> new NotFoundException("No silver.patients row with id " + silverPatientB));
        return matcher.score(a, b, settingsLoader.load());
    }
}
