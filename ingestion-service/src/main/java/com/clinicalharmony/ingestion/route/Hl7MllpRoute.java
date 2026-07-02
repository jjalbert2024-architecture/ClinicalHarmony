package com.clinicalharmony.ingestion.route;

import com.clinicalharmony.ingestion.service.Hl7MessageProcessor;
import com.clinicalharmony.ingestion.service.Hl7ProcessingResult;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * MLLP listener for real-time HL7 v2 interfaces (ADT, ORU, etc.). Every inbound message is
 * landed in bronze.raw_messages via {@link Hl7MessageProcessor} before an ACK/NAK is returned
 * over the same TCP connection, satisfying the MLLP request/response contract.
 */
@Component
public class Hl7MllpRoute extends RouteBuilder {

    private final Hl7MessageProcessor processor;

    public Hl7MllpRoute(Hl7MessageProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        from("mllp://0.0.0.0:{{ingestion.hl7.mllp-port}}")
                .routeId("hl7-mllp-intake")
                .log("Received HL7 message on MLLP port {{ingestion.hl7.mllp-port}}")
                .process(exchange -> {
                    String rawMessage = exchange.getMessage().getBody(String.class);
                    Hl7ProcessingResult result = processor.process(rawMessage);
                    exchange.getMessage().setBody(result.responseMessage());
                    exchange.getMessage().setHeader("ChConsumedRawMessageId", result.rawMessageId());
                })
                .log("Responded to HL7 message, bronze.raw_messages.id=${header.ChConsumedRawMessageId}");
    }
}
