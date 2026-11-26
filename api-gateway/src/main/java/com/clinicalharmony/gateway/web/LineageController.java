package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.read.ListWindow;
import com.clinicalharmony.gateway.read.LineageQueries;
import com.clinicalharmony.gateway.read.Page;
import com.clinicalharmony.gateway.read.SilverQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Data Lineage view: lineage chains traced from any layer, the layer browser's Bronze messages (incl. quarantined
 * ones) and Silver patients, and one Bronze message or Silver patient with links to the layers around it.
 */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class LineageController {

    private final LineageQueries lineage;
    private final SilverQueries silver;

    public LineageController(LineageQueries lineage, SilverQueries silver) {
        this.lineage = lineage;
        this.silver = silver;
    }

    /**
     * Trace one record. Exactly one of {@code fhirResourceId} (gold.fhir_resources.id), {@code masterPatientId}
     * (gold.master_patients.id), {@code silverPatientId} (silver.patients.id) or {@code bronzeMessageId}
     * (bronze.raw_messages.id).
     */
    @GetMapping("/lineage")
    public LineageQueries.LineageGraph lineage(@RequestParam(required = false) Long fhirResourceId,
                                               @RequestParam(required = false) Long masterPatientId,
                                               @RequestParam(required = false) Long silverPatientId,
                                               @RequestParam(required = false) Long bronzeMessageId) {
        long given = Stream.of(fhirResourceId, masterPatientId, silverPatientId, bronzeMessageId).filter(Objects::nonNull).count();
        if (given != 1) {
            throw ApiException.badRequest(
                    "Pass exactly one of fhirResourceId, masterPatientId, silverPatientId or bronzeMessageId");
        }
        if (fhirResourceId != null) {
            return lineage.forFhirResource(fhirResourceId);
        }
        if (masterPatientId != null) {
            return lineage.forMasterPatient(masterPatientId);
        }
        return silverPatientId != null ? lineage.forSilverPatient(silverPatientId) : lineage.forBronzeMessage(bronzeMessageId);
    }

    /** Layer browser, Silver tab: Silver patients, newest first. */
    @GetMapping("/silver/patients")
    public Page<SilverQueries.SilverPatientSummary> silverPatients(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_.-]{1,50}") String sourceSystem,
            @RequestParam(required = false) @Pattern(regexp = SilverQueries.VALIDATION_STATUSES) String validationStatus,
            @RequestParam(required = false) @Min(1) Long idFrom,
            @RequestParam(required = false) @Min(1) Long idTo,
            @RequestParam(required = false) @Pattern(regexp = ListWindow.TIME_ORDERS) String order,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return silver.patients(q, sourceSystem, validationStatus, new ListWindow(idFrom, idTo, order), page, size);
    }

    @GetMapping("/silver/source-systems")
    public List<SilverQueries.SourceCount> silverSourceSystems() {
        return silver.sourceSystems();
    }

    /** One Silver patient: its Bronze messages, clinical rows (with their Gold resources), findings and master. */
    @GetMapping("/silver/patients/{id}")
    public SilverQueries.SilverPatientDetail silverPatient(@PathVariable long id) {
        return silver.patient(id);
    }

    /** Which Silver patient a row belongs to — resolves lineage refs like {@code silver.allergies/165}. */
    @GetMapping("/silver/rows/{table}/{id}")
    public SilverQueries.RowOwner silverRow(@PathVariable @Pattern(regexp = "patients|conditions|observations|medications|allergies|claims") String table,
                                            @PathVariable long id) {
        return silver.owner(table, id);
    }

    @GetMapping("/lineage/summary")
    public List<LineageQueries.StepCount> summary() {
        return lineage.summary();
    }

    @GetMapping("/bronze/messages")
    public Page<LineageQueries.BronzeSummary> bronzeMessages(
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|PROCESSING|PROCESSED|FAILED|QUARANTINED") String status,
            @RequestParam(required = false) @Pattern(regexp = "HL7V2|FHIR_JSON|CLAIMS_CSV|CCDA_XML") String messageType,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z][A-Z0-9]{2}") String hl7Type,
            @RequestParam(required = false) @Size(max = 100) String sourceSystem,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Min(1) Long idFrom,
            @RequestParam(required = false) @Min(1) Long idTo,
            @RequestParam(required = false) @Pattern(regexp = ListWindow.TIME_ORDERS) String order,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return lineage.bronzeMessages(status, messageType, hl7Type, sourceSystem, q, new ListWindow(idFrom, idTo, order), page,
                size);
    }

    @GetMapping("/bronze/messages/{id}")
    public LineageQueries.BronzeDetail bronzeMessage(@PathVariable long id) {
        return lineage.bronzeMessage(id);
    }
}
