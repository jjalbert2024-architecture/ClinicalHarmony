package com.clinicalharmony.ingestion.route;

import com.clinicalharmony.ingestion.kafka.Hl7MllpIntakeService;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mllp.MllpConstants;
import org.springframework.stereotype.Component;

/**
 * MLLP listener for real-time HL7 v2 interfaces (ADT, ORU, etc.). Since Phase 9a every inbound message
 * is published to Kafka topic {@code clinicalharmony.bronze.hl7v2.raw} by {@link Hl7MllpIntakeService}
 * before the ACK goes back over the same TCP connection: {@code AA} once Kafka confirms the write
 * (acks=all), {@code AE} if it can't — so a sender never gets AA for a message that could be lost.
 * bronze-listener-service then stores it in {@code bronze.raw_messages} (PENDING or QUARANTINED).
 */
@Component
public class Hl7MllpRoute extends RouteBuilder {

    private final Hl7MllpIntakeService intake;

    public Hl7MllpRoute(Hl7MllpIntakeService intake) {
        this.intake = intake;
    }

    @Override
    public void configure() {
        from("mllp://0.0.0.0:{{ingestion.hl7.mllp-port}}")
                .routeId("hl7-mllp-intake")
                .process(exchange -> {
                    String rawMessage = exchange.getMessage().getBody(String.class);
                    Hl7MllpIntakeService.Outcome outcome = intake.handle(rawMessage);
                    // The MLLP consumer sends the exchange PROPERTY below, not the body; without it
                    // camel-mllp auto-generates an AA from the inbound MSH regardless of the outcome.
                    exchange.setProperty(MllpConstants.MLLP_ACKNOWLEDGEMENT_STRING, outcome.ack());
                    exchange.getMessage().setBody(outcome.ack());
                });
    }
}
