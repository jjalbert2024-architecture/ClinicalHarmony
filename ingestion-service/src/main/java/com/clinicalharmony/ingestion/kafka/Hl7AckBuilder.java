package com.clinicalharmony.ingestion.kafka;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the HL7 v2.5 ACK returned over MLLP once Kafka has (or hasn't) taken the message. Built by
 * hand from the {@link Hl7Envelope} rather than HAPI's {@code generateACK()}, because a message that
 * won't parse is still ACCEPTED here: Bronze keeps everything, and bronze-listener-service quarantines it.
 */
public final class Hl7AckBuilder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** {@code AA}: Kafka holds the message (acks=all). */
    public static String accept(Hl7Envelope envelope, String ackControlId) {
        return build(envelope, ackControlId, "AA", null);
    }

    /** {@code AE}: Kafka didn't confirm — nothing was stored, the sender should retry. */
    public static String error(Hl7Envelope envelope, String ackControlId, String reason) {
        return build(envelope, ackControlId, "AE", reason);
    }

    private static String build(Hl7Envelope e, String ackControlId, String code, String reason) {
        String controlId = e.controlId() == null ? "UNKNOWN" : clean(e.controlId());
        String trigger = e.triggerEvent() == null ? "" : clean(e.triggerEvent());
        StringBuilder sb = new StringBuilder()
                .append("MSH|^~\\&|CLINICALHARMONY|INGESTION-SERVICE|")
                .append(nz(e.sendingApplication())).append('|')
                .append(nz(e.sendingFacility())).append('|')
                .append(ZonedDateTime.now(ZoneOffset.UTC).format(TS)).append("||ACK^").append(trigger).append("^ACK|")
                .append(clean(ackControlId)).append("|P|2.5\r")
                .append("MSA|").append(code).append('|').append(controlId);
        if (reason != null) {
            sb.append('|').append(clean(reason));
        }
        return sb.append('\r').toString();
    }

    private static String nz(String s) {
        return s == null ? "" : clean(s);
    }

    /** Strip HL7 delimiters and segment breaks out of free text. */
    private static String clean(String s) {
        return s.replaceAll("[|^~\\\\&\r\n]", " ");
    }

    private Hl7AckBuilder() {
    }
}
