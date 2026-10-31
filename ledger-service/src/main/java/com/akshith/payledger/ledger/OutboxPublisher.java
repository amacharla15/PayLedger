package com.akshith.payledger.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

// Moves committed outbox rows to Kafka, or to Step Functions for rows whose topic starts with "sfn:".
// A crash after the send but before the commit re-sends the rows, so delivery is at-least-once: the Kafka
// consumer deduplicates by transfer id, and Step Functions starts each execution name only once.
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    record Row(long id, String topic, String key, String payload, String traceparent) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getLong("id"), rs.getString("topic"),
            rs.getString("msg_key"), rs.getString("payload"), rs.getString("traceparent"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final SfnClient sfn; // null when AWS is disabled
    private final String stateMachineArn;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka, MeterRegistry meters,
                           Optional<SfnClient> sfn, @Value("${aws.state-machine-arn}") String stateMachineArn) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.sfn = sfn.orElse(null);
        this.stateMachineArn = stateMachineArn;
        Gauge.builder("outbox.pending", () -> jdbc.queryForObject("select count(*) from outbox where sent_at is null", Long.class))
                .register(meters);
    }

    @Scheduled(fixedDelay = 500)
    public void publish() {
        try (Scope ignored = TraceContext.unsampled()) {
            // Keep going while batches come back full, so a backlog drains without a 500 ms pause per batch.
            int sent;
            do {
                sent = publishBatch();
            } while (sent == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("outbox publish failed, will retry: {}", e.getMessage());
        }
    }

    int publishBatch() {
        return tx.execute(s -> {
            // SKIP LOCKED: a second ledger replica polling at the same moment takes other rows instead of the same ones.
            // Without AWS, saga rows are skipped so they cannot block the drain loop.
            List<Row> rows = jdbc.query("""
                    select id, topic, msg_key, payload, traceparent from outbox
                    where sent_at is null and (? or topic not like 'sfn:%')
                    order by id limit ? for update skip locked""", ROW, sfn != null, BATCH_SIZE);
            if (rows.isEmpty()) {
                return 0;
            }
            CompletableFuture<?>[] acks = rows.stream().map(this::send).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(acks).orTimeout(10, TimeUnit.SECONDS).join();
            jdbc.update("update outbox set sent_at = now() where id = any(?)",
                    (Object) rows.stream().map(Row::id).toArray(Long[]::new));
            return rows.size();
        });
    }

    // The send runs inside the stored trace context, so the Kafka publish joins the original request's trace.
    private CompletableFuture<?> send(Row row) {
        if (row.topic().startsWith("sfn:")) {
            startExecution(row);
            return CompletableFuture.completedFuture(null);
        }
        var record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        if (row.traceparent() != null) {
            record.headers().add("traceparent", row.traceparent().getBytes(StandardCharsets.UTF_8));
        }
        try (Scope ignored = TraceContext.restore(row.traceparent())) {
            return kafka.send(record);
        }
    }

    private void startExecution(Row row) {
        try (Scope ignored = TraceContext.restore(row.traceparent())) {
            sfn.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .name(row.key())
                    .input(row.payload())
                    .build());
        } catch (ExecutionAlreadyExistsException e) {
            log.info("execution {} already started", row.key());
        }
    }
}
