package com.akshith.payledger.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

// Token bucket per user: 20 tokens, refilled at 20 per minute.
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final int CAPACITY = 20;
    private static final double TOKENS_PER_MS = 20 / 60_000.0;

    private final StringRedisTemplate redis;
    private final Counter rejected;
    private final RedisScript<Long> script = RedisScript.of(new ClassPathResource("token_bucket.lua"), Long.class);

    public RateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.rejected = meters.counter("rate_limited");
    }

    public void check(String username) {
        if (!tryAcquire(username)) {
            rejected.increment();
            throw new ApiException(ErrorType.BAD_REQUEST, "rate limited");
        }
    }

    boolean tryAcquire(String username) {
        try {
            Long allowed = redis.execute(script, List.of("rate:" + username),
                    String.valueOf(CAPACITY), String.valueOf(TOKENS_PER_MS));
            return allowed != null && allowed == 1;
        } catch (DataAccessException e) {
            // Fail open: a Redis outage should not take payments down with it.
            log.warn("rate limiter unavailable, allowing request: {}", e.getMessage());
            return true;
        }
    }
}
