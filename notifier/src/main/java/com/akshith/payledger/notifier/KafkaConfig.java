package com.akshith.payledger.notifier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    // 3 retries at 0.5s, 1s, 2s; then the record goes to <topic>.DLT with the exception in its headers.
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        Counter dlt = meters.counter("dlt");
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, e) -> new TopicPartition(record.topic() + ".DLT", -1));
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2);
        return new DefaultErrorHandler((record, e) -> {
            log.warn("dead-lettering {}-{}@{} after retries: {}", record.topic(), record.partition(), record.offset(),
                    NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            dlt.increment();
            recoverer.accept(record, e);
        }, backOff);
    }
}
