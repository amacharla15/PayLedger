package com.akshith.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.akshith.payledger.proto.Withdrawal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SendTaskSuccessRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.TaskTimedOutException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

// AWS is replaced by Mockito fakes; Postgres is real.
@SpringBootTest(properties = "grpc.port=0")
@Import(TestcontainersConfig.class)
class WithdrawalTest {

    @Autowired
    WithdrawalRepository withdrawals;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    SqsClient sqs;
    SfnClient sfn;
    WithdrawalWorker worker;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsClient.class);
        sfn = mock(SfnClient.class);
        worker = new WithdrawalWorker(sqs, sfn, withdrawals, json, "https://sqs.test/withdrawal-actions");
    }

    @Test
    void withdrawHoldsTheMoneyAndQueuesTheSaga() {
        long a = account("wendy", 1000);

        Withdrawal w = withdrawals.withdraw("wendy", a, 300, key());

        assertThat(w.getStatus()).isEqualTo("PENDING");
        assertThat(balance(a)).isEqualTo(700);
        assertThat(held(a)).isEqualTo(300);
        assertThat(jdbc.queryForObject("select count(*) from outbox where topic = 'sfn:withdrawal' and payload like ?",
                Long.class, "%\"withdrawalId\":" + w.getId() + ",%")).isEqualTo(1);
        assertInvariants();
    }

    @Test
    void sameKeyTwiceHoldsOnce() {
        long a = account("wendy", 1000);
        String key = key();

        Withdrawal first = withdrawals.withdraw("wendy", a, 300, key);
        Withdrawal second = withdrawals.withdraw("wendy", a, 300, key);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getDuplicate()).isTrue();
        assertThat(balance(a)).isEqualTo(700);
        assertThat(held(a)).isEqualTo(300);
    }

    @Test
    void overdraftAndForeignWithdrawalsAreRejected() {
        long a = account("wendy", 100);

        assertThatThrownBy(() -> withdrawals.withdraw("wendy", a, 101, key())).hasMessage("insufficient funds");
        assertThatThrownBy(() -> withdrawals.withdraw("mallory", a, 50, key())).isInstanceOf(LedgerException.class);
        Withdrawal w = withdrawals.withdraw("wendy", a, 50, key());
        assertThatThrownBy(() -> withdrawals.get("mallory", w.getId())).isInstanceOf(LedgerException.class);
        assertThat(balance(a)).isEqualTo(50);
    }

    @Test
    void settleRemovesTheHold() {
        long a = account("sam", 1000);
        Withdrawal w = withdrawals.withdraw("sam", a, 300, key());

        worker.handle(message("SETTLE", w.getId(), "token-settle"));

        assertThat(status(w)).isEqualTo("SETTLED");
        assertThat(balance(a)).isEqualTo(700);
        assertThat(held(a)).isZero();
        verify(sfn).sendTaskSuccess(argThat((SendTaskSuccessRequest r) ->
                r.taskToken().equals("token-settle") && r.output().contains("SETTLED")));
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        assertInvariants();
    }

    @Test
    void releaseReturnsTheHoldToTheBalance() {
        long a = account("rita", 1000);
        Withdrawal w = withdrawals.withdraw("rita", a, 300, key());

        worker.handle(message("RELEASE", w.getId(), "token-release"));

        assertThat(status(w)).isEqualTo("RELEASED");
        assertThat(balance(a)).isEqualTo(1000);
        assertThat(held(a)).isZero();
        assertInvariants();
    }

    @Test
    void duplicateAndLateMessagesAreNoOps() {
        long a = account("dora", 1000);
        Withdrawal w = withdrawals.withdraw("dora", a, 300, key());

        worker.handle(message("SETTLE", w.getId(), "t1"));
        worker.handle(message("SETTLE", w.getId(), "t1"));
        worker.handle(message("RELEASE", w.getId(), "t2"));

        assertThat(status(w)).isEqualTo("SETTLED");
        assertThat(balance(a)).isEqualTo(700);
        assertThat(held(a)).isZero();
        verify(sqs, times(3)).deleteMessage(any(DeleteMessageRequest.class));
        assertInvariants();
    }

    @Test
    void expiredTaskTokenStillDeletesTheMessage() {
        long a = account("tess", 1000);
        Withdrawal w = withdrawals.withdraw("tess", a, 300, key());
        when(sfn.sendTaskSuccess(any(SendTaskSuccessRequest.class)))
                .thenThrow(TaskTimedOutException.builder().message("task timed out").build());

        worker.handle(message("RELEASE", w.getId(), "expired"));

        assertThat(status(w)).isEqualTo("RELEASED");
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void pollHandlesEveryReceivedMessage() {
        long a = account("paul", 1000);
        Withdrawal w1 = withdrawals.withdraw("paul", a, 100, key());
        Withdrawal w2 = withdrawals.withdraw("paul", a, 200, key());
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(message("SETTLE", w1.getId(), "p1"), message("RELEASE", w2.getId(), "p2"))
                .build());

        worker.poll();

        assertThat(status(w1)).isEqualTo("SETTLED");
        assertThat(status(w2)).isEqualTo("RELEASED");
        assertThat(balance(a)).isEqualTo(900);
        assertInvariants();
    }

    @Test
    void outboxPublisherStartsTheSagaWithAStableExecutionName() {
        String arn = "arn:aws:states:us-west-2:123456789012:stateMachine:payledger-withdrawal";
        var publisher = new OutboxPublisher(jdbc, tx, kafka, new SimpleMeterRegistry(), Optional.of(sfn), arn);
        long a = account("olga", 1000);
        Withdrawal w = withdrawals.withdraw("olga", a, 250, key());

        while (publisher.publishBatch() > 0) {
            // drain everything, including rows left by other tests
        }

        verify(sfn).startExecution(argThat((StartExecutionRequest r) -> r.stateMachineArn().equals(arn)
                && r.name().startsWith("withdrawal-" + w.getId() + "-")
                && r.input().contains("\"amountCents\":250")));
        assertThat(jdbc.queryForObject("select count(*) from outbox where topic = 'sfn:withdrawal' and sent_at is null",
                Long.class)).isZero();
    }

    private void assertInvariants() {
        assertThat(jdbc.queryForList("""
                select a.id from accounts a left join entries e on e.account_id = a.id
                group by a.id, a.balance_cents
                having a.balance_cents <> coalesce(sum(e.amount_cents), 0)""", Long.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                select a.id from accounts a left join withdrawals w on w.account_id = a.id and w.status = 'PENDING'
                group by a.id, a.held_cents
                having a.held_cents <> coalesce(sum(w.amount_cents), 0)""", Long.class)).isEmpty();
    }

    private static Message message(String action, long withdrawalId, String token) {
        return Message.builder()
                .body("{\"action\":\"" + action + "\",\"withdrawalId\":" + withdrawalId + ",\"taskToken\":\"" + token + "\"}")
                .receiptHandle("receipt-" + token)
                .build();
    }

    private long account(String owner, long cents) {
        long id = ledger.openAccount(owner).getId();
        ledger.deposit(owner, id, cents);
        return id;
    }

    private String status(Withdrawal w) {
        return jdbc.queryForObject("select status from withdrawals where id = ?", String.class, w.getId());
    }

    private long balance(long id) {
        return jdbc.queryForObject("select balance_cents from accounts where id = ?", Long.class, id);
    }

    private long held(long id) {
        return jdbc.queryForObject("select held_cents from accounts where id = ?", Long.class, id);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
