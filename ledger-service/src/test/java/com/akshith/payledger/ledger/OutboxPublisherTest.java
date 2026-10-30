package com.akshith.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(properties = "grpc.port=0")
@Import(TestcontainersConfig.class)
class OutboxPublisherTest {

    @Autowired
    LedgerRepository ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Test
    void transferIsPublishedAndOutboxRowMarkedSent() throws InterruptedException {
        long a = ledger.openAccount("kim").getId();
        long b = ledger.openAccount("lee").getId();
        ledger.deposit("kim", a, 500);
        long transferId = ledger.transfer("kim", a, b, 120, UUID.randomUUID().toString()).getTransfer().getId();
        String marker = "\"transferId\":" + transferId + ",";

        List<ConsumerRecord<String, String>> records = consume("transfers", r -> r.value().contains(marker), 1);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(String.valueOf(a));
        assertThat(records.getFirst().value()).contains("\"amountCents\":120", "\"fromOwner\":\"kim\"", "\"toOwner\":\"lee\"");
        for (int i = 0; i < 50 && unsent(marker) > 0; i++) {
            Thread.sleep(100); // the ack arrives before the commit that sets sent_at
        }
        assertThat(unsent(marker)).isZero();
    }

    @Test
    void concurrentPublishersSendEachRowOnce() throws Exception {
        jdbc.update("""
                insert into outbox (topic, msg_key, payload)
                select 'outbox-race', (g % 7)::text, 'row-' || g from generate_series(1, 1000) g""");
        // Two extra publishers race the scheduled one, all three polling the same table.
        OutboxPublisher first = new OutboxPublisher(jdbc, tx, kafka, new SimpleMeterRegistry());
        OutboxPublisher second = new OutboxPublisher(jdbc, tx, kafka, new SimpleMeterRegistry());
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> drain(start, first, firstCount));
        pool.submit(() -> drain(start, second, secondCount));
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<ConsumerRecord<String, String>> records = consume("outbox-race", r -> true, 1000);

        assertThat(records).hasSize(1000);
        assertThat(records.stream().map(ConsumerRecord::value).distinct()).hasSize(1000);
        assertThat(firstCount.get()).isPositive();
        assertThat(secondCount.get()).isPositive();
    }

    private static Void drain(CountDownLatch start, OutboxPublisher publisher, AtomicInteger count) throws InterruptedException {
        start.await();
        int n;
        while ((n = publisher.publishBatch()) > 0) {
            count.addAndGet(n);
        }
        return null;
    }

    private long unsent(String payloadFragment) {
        return jdbc.queryForObject("select count(*) from outbox where sent_at is null and payload like ?", Long.class,
                "%" + payloadFragment + "%");
    }

    interface Match {
        boolean test(ConsumerRecord<String, String> record);
    }

    // Reads the topic from the start for a few seconds after the expected count is reached, to catch duplicates.
    private List<ConsumerRecord<String, String>> consume(String topic, Match match, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            long settleUntil = Long.MAX_VALUE;
            while (System.currentTimeMillis() < Math.min(deadline, settleUntil)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    if (match.test(r)) {
                        found.add(r);
                    }
                }
                if (found.size() >= expected && settleUntil == Long.MAX_VALUE) {
                    settleUntil = System.currentTimeMillis() + 2_000;
                }
            }
        }
        return found;
    }
}
