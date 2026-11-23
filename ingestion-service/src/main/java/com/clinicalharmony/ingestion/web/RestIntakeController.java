package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.intake.IntakeFormat;
import com.clinicalharmony.ingestion.intake.RestIntakeResult;
import com.clinicalharmony.ingestion.intake.RestIntakeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST intake (9d: publish to Kafka; bronze-listener-service writes Bronze). Every endpoint answers
 * <ul>
 *   <li><b>202</b> — passed the syntax check and Kafka confirmed it;</li>
 *   <li><b>422</b> — failed the syntax check; published anyway, so Bronze keeps it as QUARANTINED;</li>
 *   <li><b>503</b> + {@code Retry-After} — Kafka didn't confirm, nothing stored (see {@link IntakeExceptionHandler});</li>
 *   <li><b>413</b> — too large for one Kafka record; <b>400</b> — unusable X-Source-System.</li>
 * </ul>
 * The correlation id (X-Correlation-Id from the caller, else {@code rest-<12 hex>}) is echoed as a header.
 */
@RestController
@RequestMapping("/api/ingestion")
public class RestIntakeController {

    static final String SOURCE = "X-Source-System";
    static final String CORRELATION = "X-Correlation-Id";

    private final RestIntakeService intake;

    public RestIntakeController(RestIntakeService intake) {
        this.intake = intake;
    }

    /** HL7 v2 (\r, \n or \r\n between segments). The source system is MSH-4, as on MLLP. */
    @PostMapping(value = "/hl7", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestIntakeResult> hl7(@RequestBody String body,
                                                @RequestHeader(name = CORRELATION, required = false) String correlationId) {
        return respond(intake.accept(IntakeFormat.HL7V2, body, null, correlationId));
    }

    /** A FHIR resource or Bundle. */
    @PostMapping(value = "/fhir", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/fhir+json"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestIntakeResult> fhir(@RequestBody String body,
                                                 @RequestHeader(name = SOURCE, required = false) String sourceSystem,
                                                 @RequestHeader(name = CORRELATION, required = false) String correlationId) {
        return respond(intake.accept(IntakeFormat.FHIR_JSON, body, sourceSystem, correlationId));
    }

    /** A whole claims CSV file; Bronze gets one row per claim line, or the whole file quarantined. */
    @PostMapping(value = "/claims", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestIntakeResult> claims(@RequestBody String body,
                                                   @RequestHeader(name = SOURCE, required = false) String sourceSystem,
                                                   @RequestHeader(name = CORRELATION, required = false) String correlationId) {
        return respond(intake.accept(IntakeFormat.CLAIMS_CSV, body, sourceSystem, correlationId));
    }

    /** A C-CDA document (HL7 v3 ClinicalDocument). */
    @PostMapping(value = "/ccda", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestIntakeResult> ccda(@RequestBody String body,
                                                 @RequestHeader(name = SOURCE, required = false) String sourceSystem,
                                                 @RequestHeader(name = CORRELATION, required = false) String correlationId) {
        return respond(intake.accept(IntakeFormat.CCDA_XML, body, sourceSystem, correlationId));
    }

    private static ResponseEntity<RestIntakeResult> respond(RestIntakeResult result) {
        return ResponseEntity.status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_ENTITY)
                .header(CORRELATION, result.correlationId())
                .body(result);
    }
}
