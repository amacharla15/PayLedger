package com.akshith.payledger.gateway;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public record AuthPayload(String accessToken, String refreshToken) {
    }

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JwtEncoder jwtEncoder;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, StringRedisTemplate redis, JwtEncoder jwtEncoder, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.jwtEncoder = jwtEncoder;
        this.passwords = passwords;
    }

    public AuthPayload register(String username, String password) {
        if (username.isBlank() || password.length() < 8) {
            throw new ApiException(ErrorType.BAD_REQUEST, "username is required and password needs at least 8 characters");
        }
        try {
            jdbc.update("insert into users (username, password_hash) values (?, ?)", username, passwords.encode(password));
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorType.BAD_REQUEST, "username already taken");
        }
        return issue(username);
    }

    public AuthPayload login(String username, String password) {
        List<String> hashes = jdbc.queryForList("select password_hash from users where username = ?", String.class, username);
        if (hashes.isEmpty() || !passwords.matches(password, hashes.getFirst())) {
            throw new ApiException(ErrorType.UNAUTHORIZED, "invalid username or password");
        }
        return issue(username);
    }

    public AuthPayload refresh(String refreshToken) {
        // GETDEL is atomic: a refresh token can be exchanged exactly once, even under concurrent requests.
        String username = redis.opsForValue().getAndDelete(key(refreshToken));
        if (username == null) {
            throw new ApiException(ErrorType.UNAUTHORIZED, "invalid refresh token");
        }
        return issue(username);
    }

    public boolean logout(String refreshToken) {
        return redis.delete(key(refreshToken));
    }

    private AuthPayload issue(String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TTL))
                .build();
        String accessToken = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(refreshToken), username, REFRESH_TTL);
        return new AuthPayload(accessToken, refreshToken);
    }

    private static String key(String refreshToken) {
        return "refresh:" + refreshToken;
    }
}
