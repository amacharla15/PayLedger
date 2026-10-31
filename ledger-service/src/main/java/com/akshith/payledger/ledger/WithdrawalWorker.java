package com.akshith.payledger.ledger;

import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.InvalidTokenException;
import software.amazon.awssdk.services.sfn.model.SendTaskSuccessRequest;
import software.amazon.awssdk.services.sfn.model.TaskTimedOutException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

// The state machine's Settle and Release steps put a message with a task token on the SQS queue and wait.
// This worker applies the action to the ledger, hands the token back with SendTaskSuccess, then deletes the
// message. If it fails before the delete, SQS redelivers the message and the repeat is a no-op.
@Component
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class WithdrawalWorker {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalWorker.class);

    public record Action(String action, long withdrawalId, String taskToken) {
    }

    private final SqsClient sqs;
    private final SfnClient sfn;
    private final WithdrawalRepository withdrawals;
    private final JsonMapper json;
    private final String queueUrl;
    private volatile boolean running;

    public WithdrawalWorker(SqsClient sqs, SfnClient sfn, WithdrawalRepository withdrawals, JsonMapper json,
                            @Value("${aws.withdrawal-queue-url}") String queueUrl) {
        this.sqs = sqs;
        this.sfn = sfn;
        this.withdrawals = withdrawals;
        this.json = json;
        this.queueUrl = queueUrl;
    }

    // A long poll blocks for up to 20 s, so the worker gets its own thread instead of sharing the
    // scheduler thread with the outbox publisher.
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        Thread.ofVirtual().name("withdrawal-worker").start(() -> {
            while (running) {
                try (Scope ignored = TraceContext.unsampled()) {
                    poll();
                } catch (RuntimeException e) {
                    log.warn("polling {} failed: {}", queueUrl, e.getMessage());
                    pause();
                }
            }
        });
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        running = false;
    }

    void poll() {
        var request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();
        for (Message message : sqs.receiveMessage(request).messages()) {
            try {
                handle(message);
            } catch (RuntimeException e) {
                log.warn("withdrawal action failed, SQS will redeliver it: {}", e.getMessage());
            }
        }
    }

    void handle(Message message) {
        Action action = json.readValue(message.body(), Action.class);
        String status = switch (action.action()) {
            case "SETTLE" -> withdrawals.settle(action.withdrawalId());
            case "RELEASE" -> withdrawals.release(action.withdrawalId());
            default -> throw new IllegalArgumentException("unknown action " + action.action());
        };
        try {
            sfn.sendTaskSuccess(SendTaskSuccessRequest.builder()
                    .taskToken(action.taskToken())
                    .output("{\"status\":\"" + status + "\"}")
                    .build());
        } catch (TaskTimedOutException | InvalidTokenException e) {
            // The execution no longer waits for this token; the ledger is already correct, so drop the message.
            log.warn("task token for withdrawal {} is no longer valid: {}", action.withdrawalId(), e.getMessage());
        }
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
        log.info("withdrawal {} {}", action.withdrawalId(), status);
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
