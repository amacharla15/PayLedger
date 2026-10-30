package com.akshith.payledger.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Moves committed outbox rows to Kafka. A crash after the send but before the commit re-sends the rows,
// so delivery is at-least-once and the consumer deduplicates by transfer id.
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    record Row(long id, String topic, String key, String payload, String traceparent) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getLong("id"), rs.getString("topic"),
            rs.getString("msg_key"), rs.getString("payload"), rs.getString("traceparent"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        Gauge.builder("outbox.pending", () -> jdbc.queryForObject("select count(*) from outbox where sent_at is null", Long.class))
                .register(meters);
    }

    @Scheduled(fixedDelay = 500)
    public void publish() {
        try (Scope ignored = TraceContext.unsampled()) {
            publishBatch();
        } catch (RuntimeException e) {
            log.warn("outbox publish failed, will retry: {}", e.getMessage());
        }
    }

    int publishBatch() {
        return tx.execute(s -> {
            // SKIP LOCKED: a second ledger replica polling at the same moment takes other rows instead of the same ones.
            List<Row> rows = jdbc.query("""
                    select id, topic, msg_key, payload, traceparent from outbox where sent_at is null
                    order by id limit 100 for update skip locked""", ROW);
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
        var record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        if (row.traceparent() != null) {
            record.headers().add("traceparent", row.traceparent().getBytes(StandardCharsets.UTF_8));
        }
        try (Scope ignored = TraceContext.restore(row.traceparent())) {
            return kafka.send(record);
        }
    }
}
