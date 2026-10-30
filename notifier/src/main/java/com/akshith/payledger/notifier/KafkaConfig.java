package com.akshith.payledger.notifier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {

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
            dlt.increment();
            recoverer.accept(record, e);
        }, backOff);
    }
}
