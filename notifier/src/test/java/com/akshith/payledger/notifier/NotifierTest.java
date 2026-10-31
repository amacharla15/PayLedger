package com.akshith.payledger.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(NotifierTest.Containers.class)
class NotifierTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.4.0");
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:8.10").withExposedPorts(6379);
        }

        @Bean
        NewTopic transfers() {
            return TopicBuilder.name("transfers").partitions(3).build();
        }

        @Bean
        NewTopic deadLetters() {
            return TopicBuilder.name("transfers.DLT").partitions(3).build();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    MeterRegistry meters;

    @Autowired
    KafkaContainer kafkaContainer;

    @Test
    void sameMessageTwiceNotifiesOnce() throws InterruptedException {
        double before = count("notifications");
        long id = randomId();
        long marker = randomId();
        // Same key means same partition, so the marker is processed after both copies.
        kafka.send("transfers", "1", event(id, 500)).join();
        kafka.send("transfers", "1", event(id, 500)).join();
        kafka.send("transfers", "1", event(marker, 1)).join();

        waitFor(() -> count("notifications") >= before + 2);

        assertThat(count("notifications") - before).isEqualTo(2);
    }

    @Test
    void poisonMessageLandsInDeadLetterTopicAfterRetries() {
        long id = randomId();
        kafka.send("transfers", "2", event(id, 13)).join();

        List<ConsumerRecord<String, String>> dead = consume("transfers.DLT", "\"transferId\":" + id + ",");

        assertThat(dead).hasSize(1);
        assertThat(new String(dead.getFirst().headers().lastHeader("kafka_dlt-exception-message").value()))
                .contains("poison transfer " + id);
        assertThat(count("dlt")).isPositive();
    }

    private double count(String counter) {
        return meters.counter(counter).count();
    }

    private static String event(long transferId, long amountCents) {
        return """
                {"transferId":%d,"fromAccountId":1,"toAccountId":2,"amountCents":%d,"fromOwner":"ann","toOwner":"ben"}"""
                .formatted(transferId, amountCents);
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 150 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }

    private List<ConsumerRecord<String, String>> consume(String topic, String fragment) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (r.value().contains(fragment)) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }
}
