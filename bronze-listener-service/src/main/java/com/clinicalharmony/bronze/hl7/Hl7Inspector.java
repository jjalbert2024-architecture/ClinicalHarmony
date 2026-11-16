package com.clinicalharmony.bronze.hl7;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import org.springframework.stereotype.Component;

/**
 * Decides PENDING vs QUARANTINED: does the payload parse as HL7 v2 (HAPI 2.3, structures v2.5, no
 * conformance validation — same parser settings as ingestion-service's REST path), and does it have
 * the two MSH fields every later layer depends on (MSH-9 message type, MSH-10 control id)?
 *
 * <p>Bad data never throws out of here: it's a business outcome, kept in Bronze for a data steward.
 */
@Component
public class Hl7Inspector {

    public record Result(boolean parsed, String messageType, String sendingFacility, String controlId, String error) {

        static Result ok(String messageType, String sendingFacility, String controlId) {
            return new Result(true, messageType, sendingFacility, controlId, null);
        }

        static Result bad(String error) {
            return new Result(false, null, null, null, error);
        }
    }

    // PipeParser isn't documented as thread-safe and the listener runs 3 consumer threads.
    private final ThreadLocal<PipeParser> parser = ThreadLocal.withInitial(() -> {
        PipeParser p = new PipeParser();
        p.setValidationContext(ValidationContextFactory.noValidation());
        return p;
    });

    public Result inspect(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.bad("Empty payload");
        }
        // MLLP senders use \r; tolerate \n / \r\n for parsing while Bronze keeps the original bytes.
        String normalized = raw.replace("\r\n", "\r").replace("\n", "\r");
        try {
            Message message = parser.get().parse(normalized);
            Terser terser = new Terser(message);
            String code = terser.get("MSH-9-1");
            String trigger = terser.get("MSH-9-2");
            String controlId = terser.get("MSH-10");
            if (code == null || code.isBlank()) {
                return Result.bad("MSH-9 (message type) is empty");
            }
            if (controlId == null || controlId.isBlank()) {
                return Result.bad("MSH-10 (message control id) is empty");
            }
            String type = trigger == null || trigger.isBlank() ? code : code + "^" + trigger;
            return Result.ok(type, terser.get("MSH-4-1"), controlId);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Result.bad(msg.length() > 500 ? msg.substring(0, 500) : msg);
        }
    }
}
