package com.clinicalharmony.ingestion.service;

import com.clinicalharmony.ingestion.repository.RawMessageRecord;
import com.clinicalharmony.ingestion.repository.RawMessageRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Lands inbound Claims CSV files in bronze.raw_messages, one row per claim line ("ClaimsTransformer"
 * in the system architecture). Each landed payload is self-describing: the original header line
 * plus that one data line, so downstream Silver parsing never needs the rest of the file. An
 * unparseable file is quarantined whole as a single row.
 *
 * <p>Ingestion is all-or-nothing per file: the CSV is fully parsed before anything is written,
 * so a bad row quarantines the whole file rather than landing a partial claim set — a partially
 * ingested claims file would be worse for reconciliation than a rejected one. Database failures
 * during the write phase propagate (rolling back the file's rows) rather than quarantining,
 * because they say nothing about the payload's validity.
 */
@Service
public class ClaimsMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClaimsMessageProcessor.class);

    private final RawMessageRepository repository;
    private final String defaultSourceSystem;

    public ClaimsMessageProcessor(RawMessageRepository repository,
                                  @Value("${ingestion.claims.default-source-system}") String defaultSourceSystem) {
        this.repository = repository;
        this.defaultSourceSystem = defaultSourceSystem;
    }

    @Transactional
    public ClaimsProcessingResult process(String rawCsv, String sourceSystem) {
        String effectiveSource = (sourceSystem == null || sourceSystem.isBlank())
                ? defaultSourceSystem
                : sourceSystem;

        List<String> rowPayloads;
        try {
            rowPayloads = parseToRowPayloads(rawCsv);
        } catch (Exception e) {
            log.warn("Failed to parse claims CSV, quarantining whole file: {}", e.getMessage());
            long id = repository.insert(RawMessageRecord.quarantined(
                    effectiveSource, "CLAIMS_CSV", "CSV", rawCsv, Checksums.sha256(rawCsv), e.getMessage()));
            return new ClaimsProcessingResult(false, 0, List.of(id), e.getMessage());
        }

        List<Long> ids = new ArrayList<>(rowPayloads.size());
        for (String rowPayload : rowPayloads) {
            ids.add(repository.insert(RawMessageRecord.accepted(
                    effectiveSource, "CLAIMS_CSV", "CSV", rowPayload, Checksums.sha256(rowPayload))));
        }

        log.info("Landed claims file as {} rows source={} ids={}..{}",
                ids.size(), effectiveSource, ids.get(0), ids.get(ids.size() - 1));
        return new ClaimsProcessingResult(true, ids.size(), ids, null);
    }

    private static List<String> parseToRowPayloads(String rawCsv) throws Exception {
        try (CSVParser csvParser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(rawCsv))) {

            String headerLine = String.join(",", csvParser.getHeaderNames());
            List<String> rowPayloads = new ArrayList<>();
            for (CSVRecord csvRecord : csvParser) {
                if (csvRecord.size() != csvParser.getHeaderNames().size()) {
                    throw new IllegalArgumentException(
                            "Row " + csvRecord.getRecordNumber() + " has " + csvRecord.size()
                                    + " columns, header has " + csvParser.getHeaderNames().size());
                }
                rowPayloads.add(headerLine + "\n" + String.join(",", csvRecord.toList()));
            }
            if (rowPayloads.isEmpty()) {
                throw new IllegalArgumentException("CSV contains a header but no claim rows");
            }
            return rowPayloads;
        }
    }
}
