package com.akshith.payledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.10").withExposedPorts(6379);

    static RateLimiter limiter;

    @BeforeAll
    static void connect() {
        limiter = new RateLimiter(new StringRedisTemplate(factory(redis.getHost(), redis.getMappedPort(6379))), new SimpleMeterRegistry());
    }

    @Test
    void burstOfTwentyPassesAndTwentyFirstFails() {
        String user = user();
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire(user)).as("call %d", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire(user)).isFalse();
    }

    @Test
    void tokensComeBackAfterRefillTime() throws InterruptedException {
        String user = user();
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire(user);
        }
        assertThat(limiter.tryAcquire(user)).isFalse();

        Thread.sleep(3_100); // 20 per minute = one token every 3 seconds

        assertThat(limiter.tryAcquire(user)).isTrue();
        assertThat(limiter.tryAcquire(user)).isFalse();
    }

    @Test
    void fiftyConcurrentCallsLetTwentyThrough() throws InterruptedException {
        String user = user();
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(50);
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire(user)) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(20);
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        RateLimiter down = new RateLimiter(new StringRedisTemplate(factory("localhost", 1)), new SimpleMeterRegistry());

        assertThat(down.tryAcquire(user())).isTrue();
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static String user() {
        return UUID.randomUUID().toString();
    }
}
