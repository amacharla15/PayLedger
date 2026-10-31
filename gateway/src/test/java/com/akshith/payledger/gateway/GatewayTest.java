package com.akshith.payledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jwt.secret=test-secret-that-is-at-least-32-bytes-long")
@Import(TestConfig.class)
class GatewayTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    FakeLedger fakeLedger;

    HttpSyncGraphQlClient client;

    @BeforeEach
    void setUp() {
        client = HttpSyncGraphQlClient.create(RestClient.create("http://localhost:" + port + "/graphql"));
    }

    @Test
    void registerThenLoginGivesWorkingToken() {
        String user = username();
        register(user, "password123");

        ClientGraphQlResponse login = execute(null, "mutation { login(username: \"%s\", password: \"password123\") { accessToken } }", user);
        String token = login.field("login.accessToken").getValue();

        assertThat(execute(token, "{ me }").field("me").<String>getValue()).isEqualTo(user);
    }

    @Test
    void requestWithoutTokenIsRejected() {
        ClientGraphQlResponse response = execute(null, "{ me }");

        assertThat(response.getErrors()).extracting(ResponseError::getErrorType).extracting(Object::toString)
                .containsExactly("UNAUTHORIZED");
    }

    @Test
    void wrongPasswordIsRejected() {
        String user = username();
        register(user, "password123");

        ClientGraphQlResponse response = execute(null, "mutation { login(username: \"%s\", password: \"wrong-password\") { accessToken } }", user);

        assertThat(response.getErrors()).extracting(ResponseError::getMessage).containsExactly("invalid username or password");
    }

    @Test
    void refreshRotatesAndOldTokenStopsWorking() {
        String first = register(username(), "password123").get("refreshToken");

        ClientGraphQlResponse rotated = refresh(first);
        String second = rotated.field("refresh.refreshToken").getValue();

        assertThat(second).isNotEqualTo(first);
        assertThat(refresh(first).getErrors()).extracting(ResponseError::getMessage).containsExactly("invalid refresh token");
        assertThat(refresh(second).getErrors()).isEmpty();
    }

    @Test
    void logoutInvalidatesRefreshToken() {
        String token = register(username(), "password123").get("refreshToken");

        ClientGraphQlResponse logout = execute(null, "mutation { logout(refreshToken: \"%s\") }", token);

        assertThat(logout.field("logout").<Boolean>getValue()).isTrue();
        assertThat(refresh(token).getErrors()).extracting(ResponseError::getMessage).containsExactly("invalid refresh token");
    }

    @Test
    void pageOfTwentyTransfersIssuesOneGetAccountsCall() {
        String user = username();
        String token = register(user, "password123").get("accessToken");
        int before = fakeLedger.getAccountsCalls.get();

        ClientGraphQlResponse response = execute(token,
                "{ transfers(accountId: 1, first: 20) { items { id from { id ownerId } to { id ownerId } } nextCursor } }");

        List<Map<String, Object>> items = response.field("transfers.items").getValue();
        assertThat(items).hasSize(20);
        assertThat(response.field("transfers.items[0].from.ownerId").<String>getValue()).isEqualTo("owner1");
        assertThat(response.field("transfers.items[0].to.ownerId").<String>getValue()).isEqualTo("owner3");
        assertThat(fakeLedger.getAccountsCalls.get() - before).isEqualTo(1);
        assertThat(fakeLedger.lastGetAccountsOwner).isEqualTo(user);
    }

    @Test
    void ledgerStatusCodesBecomeClearErrors() {
        String token = register(username(), "password123").get("accessToken");

        assertThat(transferError(token, 1)).isEqualTo("BAD_REQUEST: insufficient funds");
        assertThat(transferError(token, 2)).isEqualTo("FORBIDDEN: account 1 does not belong to you");
        assertThat(transferError(token, 3)).isEqualTo("INTERNAL_ERROR: ledger unavailable");
    }

    @Test
    void loginIsRateLimitedPerUsername() {
        String user = username();
        register(user, "password123");
        for (int i = 0; i < 19; i++) {
            assertThat(login(user).getErrors()).isEmpty();
        }

        assertThat(login(user).getErrors()).extracting(ResponseError::getMessage).containsExactly("rate limited");
    }

    private ClientGraphQlResponse login(String user) {
        return execute(null, "mutation { login(username: \"%s\", password: \"password123\") { accessToken } }", user);
    }

    private String transferError(String token, int amount) {
        ResponseError error = execute(token, """
                mutation { transfer(input: {fromAccountId: 1, toAccountId: 2, amountCents: %d, idempotencyKey: "k"}) { id } }""",
                amount).getErrors().getFirst();
        return error.getErrorType() + ": " + error.getMessage();
    }

    private Map<String, String> register(String user, String password) {
        ClientGraphQlResponse response = execute(null,
                "mutation { register(username: \"%s\", password: \"%s\") { accessToken refreshToken } }", user, password);
        return response.field("register").getValue();
    }

    private ClientGraphQlResponse refresh(String token) {
        return execute(null, "mutation { refresh(refreshToken: \"%s\") { accessToken refreshToken } }", token);
    }

    private ClientGraphQlResponse execute(String token, String document, Object... args) {
        HttpSyncGraphQlClient c = token == null ? client : client.mutate().header("Authorization", "Bearer " + token).build();
        return c.document(document.formatted(args)).executeSync();
    }

    private static String username() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
