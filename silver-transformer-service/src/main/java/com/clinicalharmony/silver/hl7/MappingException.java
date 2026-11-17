package com.clinicalharmony.silver.hl7;

/**
 * The HL7 message can't be mapped to Silver (missing required field, unsupported message type, unknown
 * code system…). A business outcome, not a technical failure: the Bronze row becomes FAILED with this
 * message, nothing is written to Silver, and the Kafka offset moves on.
 */
public class MappingException extends RuntimeException {

    public MappingException(String message) {
        super(message);
    }
}
