package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.read.Page;
import com.clinicalharmony.gateway.read.PatientQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Patient Timeline view (read side). PMI review actions live in {@link PmiController}. */
@RestController
@RequestMapping(value = "/api/v1/patients", produces = MediaType.APPLICATION_JSON_VALUE)
public class PatientController {

    private final PatientQueries patients;

    public PatientController(PatientQueries patients) {
        this.patients = patients;
    }

    @GetMapping
    public Page<PatientQueries.MasterSummary> search(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam(required = false) @Pattern(regexp = "PROVISIONAL|MATCHED|MERGED") String status,
            @RequestParam(defaultValue = "false") boolean includeMerged,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        // Asking for MERGED masters only makes sense with the inactive ones included.
        return patients.search(q, dob, status, includeMerged || "MERGED".equals(status), page, size);
    }

    @GetMapping("/{masterId}")
    public PatientQueries.MasterDetail detail(@PathVariable long masterId) {
        return patients.detail(masterId);
    }

    @GetMapping("/{masterId}/timeline")
    public PatientQueries.Timeline timeline(
            @PathVariable long masterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> types) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("'to' (" + to + ") is before 'from' (" + from + ")");
        }
        Set<String> kinds = new LinkedHashSet<>();
        if (types == null || types.isEmpty()) {
            kinds.addAll(PatientQueries.EVENT_KINDS);
        } else {
            for (String t : types) {
                String kind = t.trim().toUpperCase(Locale.ROOT);
                if (!PatientQueries.EVENT_KINDS.contains(kind)) {
                    throw ApiException.badRequest("Unknown timeline type '" + t + "'; use CONDITION, OBSERVATION, MEDICATION");
                }
                kinds.add(kind);
            }
        }
        return patients.timeline(masterId, from, to, kinds);
    }
}
