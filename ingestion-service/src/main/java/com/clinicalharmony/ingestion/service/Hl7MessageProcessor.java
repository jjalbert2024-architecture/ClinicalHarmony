package com.clinicalharmony.ingestion.service;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import com.clinicalharmony.ingestion.repository.RawMessageRecord;
import com.clinicalharmony.ingestion.repository.RawMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Parses inbound HL7 v2 payloads, lands them in bronze.raw_messages regardless of whether
 * parsing succeeds (malformed messages are quarantined, not dropped), and produces the
 * ACK/NAK to return to the sender.
 */
@Service
public class Hl7MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(Hl7MessageProcessor.class);

    private final RawMessageRepository repository;
    private final PipeParser parser;
    private final String defaultSourceSystem;

    public Hl7MessageProcessor(RawMessageRepository repository,
                                @Value("${ingestion.hl7.default-source-system}") String defaultSourceSystem) {
        this.repository = repository;
        this.defaultSourceSystem = defaultSourceSystem;
        this.parser = new PipeParser();
        this.parser.setValidationContext(ValidationContextFactory.noValidation());
    }

    public Hl7ProcessingResult process(String rawMessage) {
        String checksum = sha256(rawMessage);
        // MLLP senders always use \r per the HL7 spec, but REST/Postman clients commonly
        // submit \n or \r\n instead — normalize for parsing while landing the original
        // bytes in bronze.raw_messages untouched.
        String normalized = normalizeSegmentTerminators(rawMessage);

        try {
            Message message = parser.parse(normalized);
            Terser terser = new Terser(message);
            String messageType = terser.get("MSH-9-1") + "^" + terser.get("MSH-9-2");
            String sendingFacility = terser.get("MSH-4-1");
            String sourceSystem = (sendingFacility == null || sendingFacility.isBlank())
                    ? defaultSourceSystem
                    : sendingFacility;

            long id = repository.insert(RawMessageRecord.accepted(sourceSystem, rawMessage, checksum));
            log.info("Landed HL7 message id={} type={} source={}", id, messageType, sourceSystem);

            String ack = parser.encode(message.generateACK());
            return new Hl7ProcessingResult(true, id, messageType, ack);
        } catch (Exception e) {
            log.warn("Failed to parse HL7 message, quarantining: {}", e.getMessage());
            long id = repository.insert(RawMessageRecord.quarantined(defaultSourceSystem, rawMessage, checksum, e.getMessage()));
            return new Hl7ProcessingResult(false, id, null, buildGenericNak(normalized, e.getMessage()));
        }
    }

    private static String normalizeSegmentTerminators(String message) {
        return message.replace("\r\n", "\r").replace("\n", "\r");
    }

    private static String buildGenericNak(String rawMessage, String errorMessage) {
        String controlId = extractControlId(rawMessage);
        String reason = (errorMessage == null || errorMessage.isBlank()) ? "Unparseable HL7 message" : errorMessage;
        return "MSH|^~\\&|CLINICALHARMONY|INGESTION-SERVICE|||" + controlId + "||ACK|" + controlId + "|P|2.5\r"
                + "MSA|AE|" + controlId + "|" + reason.replace("|", " ").replace("\r", " ") + "\r";
    }

    private static String extractControlId(String rawMessage) {
        try {
            String mshSegment = rawMessage.split("[\r\n]", 2)[0];
            String[] fields = mshSegment.split("\\|", -1);
            return fields.length > 9 && !fields[9].isBlank() ? fields[9] : "UNKNOWN";
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
