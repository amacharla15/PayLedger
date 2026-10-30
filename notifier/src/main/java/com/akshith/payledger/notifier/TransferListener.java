package com.akshith.payledger.notifier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TransferListener {

    private static final Logger log = LoggerFactory.getLogger(TransferListener.class);
    private static final long POISON_AMOUNT = 13;

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Counter notifications;

    public TransferListener(StringRedisTemplate redis, JsonMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.notifications = meters.counter("notifications");
    }

    // 3 consumers for the 3 partitions; the key is the source account, so its events stay in order.
    @KafkaListener(topics = "transfers", groupId = "notifier", concurrency = "3")
    public void onTransfer(String payload) {
        TransferEvent event = json.readValue(payload, TransferEvent.class);
        String key = "processed:" + event.transferId();
        // The outbox delivers at-least-once, so the same transfer can arrive twice. Only the first SET NX wins.
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", Duration.ofDays(7)))) {
            log.info("skip duplicate transfer {}", event.transferId());
            return;
        }
        try {
            notify(event);
        } catch (RuntimeException e) {
            redis.delete(key); // release the claim so the retry can process it
            throw e;
        }
    }

    private void notify(TransferEvent event) {
        if (event.amountCents() == POISON_AMOUNT) {
            throw new IllegalStateException("poison transfer " + event.transferId());
        }
        log.info("notify {}: received {} cents from {}", event.toOwner(), event.amountCents(), event.fromOwner());
        notifications.increment();
    }
}
