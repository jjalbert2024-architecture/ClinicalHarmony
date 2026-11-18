package com.clinicalharmony.fhircompliance.integration;

import com.clinicalharmony.fhircompliance.goldbuilder.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.http.HttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Shared setup for the {@code *IntegrationTest}s: the whole Spring context over real HTTP, against
 * one throwaway Postgres 16 initialized from the real docs/db-init scripts. The container is a
 * {@code @ServiceConnection} field without {@code @Container}, so Spring Boot starts it with the
 * (cached, shared) application context rather than JUnit stopping it after each class — every
 * subclass reuses the same context and database. Skipped, not failed, without Docker.
 *
 * <p>Since Phase 9c the context also runs the gold-builder consumer and Gold outbox relay, so a real Kafka
 * broker (cp-kafka 7.6.1, KRaft — the compose image) is part of the shared setup, started the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gold-builder.retry.initial-interval=100ms",
        "gold-builder.outbox.poll-interval=200ms",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Testcontainers(disabledWithoutDocker = true)
@Import({TestJwts.class, AbstractIntegrationTest.TopicConfig.class})
abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    @TestConfiguration
    static class TopicConfig {
        /** In compose silver-transformer-service (the producer) declares this topic; here the test does. */
        @Bean
        NewTopic silverPatientChanged() {
            return TopicBuilder.name(Topics.SILVER_PATIENT_CHANGED).partitions(3).replicas(1).build();
        }
    }

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    @LocalServerPort
    protected int port;

    /** A client that sends no Authorization header. */
    protected TestRestTemplate anonymous() {
        return new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port));
    }

    /** A client whose every request carries a valid Bearer token with these scopes. */
    protected TestRestTemplate withScopes(String... scopes) {
        return withToken(TestJwts.mint(scopes));
    }

    protected TestRestTemplate withToken(String token) {
        return new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
