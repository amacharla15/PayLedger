package com.akshith.payledger.ledger;

import com.akshith.payledger.proto.Account;
import com.akshith.payledger.proto.DepositRequest;
import com.akshith.payledger.proto.GetAccountRequest;
import com.akshith.payledger.proto.GetAccountsRequest;
import com.akshith.payledger.proto.GetAccountsResponse;
import com.akshith.payledger.proto.GetWithdrawalRequest;
import com.akshith.payledger.proto.LedgerGrpc;
import com.akshith.payledger.proto.ListAccountsRequest;
import com.akshith.payledger.proto.ListAccountsResponse;
import com.akshith.payledger.proto.ListTransfersRequest;
import com.akshith.payledger.proto.ListTransfersResponse;
import com.akshith.payledger.proto.OpenAccountRequest;
import com.akshith.payledger.proto.TransferRequest;
import com.akshith.payledger.proto.TransferResult;
import com.akshith.payledger.proto.WithdrawRequest;
import com.akshith.payledger.proto.Withdrawal;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LedgerGrpcService extends LedgerGrpc.LedgerImplBase {

    private static final Logger log = LoggerFactory.getLogger(LedgerGrpcService.class);

    private final LedgerRepository ledger;
    private final WithdrawalRepository withdrawals;
    private final boolean awsEnabled;
    private final MeterRegistry meters;
    private final Timer transferLatency;

    public LedgerGrpcService(LedgerRepository ledger, WithdrawalRepository withdrawals,
                             @Value("${aws.enabled}") boolean awsEnabled, MeterRegistry meters) {
        this.ledger = ledger;
        this.withdrawals = withdrawals;
        this.awsEnabled = awsEnabled;
        this.meters = meters;
        this.transferLatency = Timer.builder("transfer.latency").publishPercentileHistogram().register(meters);
    }

    @Override
    public void openAccount(OpenAccountRequest req, StreamObserver<Account> out) {
        respond(out, () -> ledger.openAccount(req.getOwnerId()));
    }

    @Override
    public void getAccount(GetAccountRequest req, StreamObserver<Account> out) {
        respond(out, () -> ledger.getAccount(req.getOwnerId(), req.getAccountId()));
    }

    @Override
    public void getAccounts(GetAccountsRequest req, StreamObserver<GetAccountsResponse> out) {
        respond(out, () -> GetAccountsResponse.newBuilder()
                .addAllAccounts(ledger.getAccounts(req.getOwnerId(), req.getAccountIdsList()))
                .build());
    }

    @Override
    public void listAccounts(ListAccountsRequest req, StreamObserver<ListAccountsResponse> out) {
        respond(out, () -> ListAccountsResponse.newBuilder()
                .addAllAccounts(ledger.listAccounts(req.getOwnerId()))
                .build());
    }

    @Override
    public void deposit(DepositRequest req, StreamObserver<Account> out) {
        respond(out, () -> ledger.deposit(req.getOwnerId(), req.getAccountId(), req.getAmountCents()));
    }

    @Override
    public void transfer(TransferRequest req, StreamObserver<TransferResult> out) {
        respond(out, () -> transferLatency.record(() -> {
            try {
                TransferResult result = ledger.transfer(req.getOwnerId(), req.getFromAccountId(), req.getToAccountId(),
                        req.getAmountCents(), req.getIdempotencyKey());
                countTransfer(result.getDuplicate() ? "duplicate" : "ok");
                return result;
            } catch (LedgerException e) {
                if (e.reason() == LedgerException.Reason.INSUFFICIENT_FUNDS) {
                    countTransfer("insufficient_funds");
                }
                throw e;
            }
        }));
    }

    private void countTransfer(String result) {
        meters.counter("transfers", "result", result).increment();
    }

    @Override
    public void listTransfers(ListTransfersRequest req, StreamObserver<ListTransfersResponse> out) {
        long afterId = req.hasAfterId() ? req.getAfterId() : Long.MAX_VALUE;
        respond(out, () -> ledger.listTransfers(req.getOwnerId(), req.getAccountId(), req.getPageSize(), afterId));
    }

    @Override
    public void withdraw(WithdrawRequest req, StreamObserver<Withdrawal> out) {
        respond(out, () -> {
            // Without AWS nothing would ever settle or release the hold, so refuse instead of holding money forever.
            if (!awsEnabled) {
                throw new LedgerException(LedgerException.Reason.DISABLED, "withdrawals need AWS_ENABLED=true");
            }
            return withdrawals.withdraw(req.getOwnerId(), req.getAccountId(), req.getAmountCents(), req.getIdempotencyKey());
        });
    }

    @Override
    public void getWithdrawal(GetWithdrawalRequest req, StreamObserver<Withdrawal> out) {
        respond(out, () -> withdrawals.get(req.getOwnerId(), req.getWithdrawalId()));
    }

    private static <T> void respond(StreamObserver<T> out, Supplier<T> call) {
        try {
            out.onNext(call.get());
            out.onCompleted();
        } catch (LedgerException e) {
            out.onError(status(e.reason()).withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            log.error("ledger call failed", e);
            out.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
        }
    }

    private static Status status(LedgerException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> Status.NOT_FOUND;
            case NOT_OWNER -> Status.PERMISSION_DENIED;
            case INSUFFICIENT_FUNDS, DISABLED -> Status.FAILED_PRECONDITION;
            case INVALID -> Status.INVALID_ARGUMENT;
        };
    }
}
