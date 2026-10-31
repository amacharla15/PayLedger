package com.akshith.payledger.gateway;

import com.akshith.payledger.proto.Account;
import com.akshith.payledger.proto.DepositRequest;
import com.akshith.payledger.proto.GetAccountRequest;
import com.akshith.payledger.proto.GetAccountsRequest;
import com.akshith.payledger.proto.GetWithdrawalRequest;
import com.akshith.payledger.proto.LedgerGrpc;
import com.akshith.payledger.proto.ListAccountsRequest;
import com.akshith.payledger.proto.ListTransfersRequest;
import com.akshith.payledger.proto.ListTransfersResponse;
import com.akshith.payledger.proto.OpenAccountRequest;
import com.akshith.payledger.proto.TransferRequest;
import com.akshith.payledger.proto.TransferResult;
import com.akshith.payledger.proto.WithdrawRequest;
import com.akshith.payledger.proto.Withdrawal;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

@Component
public class LedgerClient {

    private final LedgerGrpc.LedgerBlockingStub stub;

    public LedgerClient(ManagedChannel channel) {
        this.stub = LedgerGrpc.newBlockingStub(channel);
    }

    public Account openAccount(String owner) {
        return call(s -> s.openAccount(OpenAccountRequest.newBuilder().setOwnerId(owner).build()));
    }

    public Account getAccount(String owner, long accountId) {
        return call(s -> s.getAccount(GetAccountRequest.newBuilder().setOwnerId(owner).setAccountId(accountId).build()));
    }

    public List<Account> getAccounts(String owner, Collection<Long> accountIds) {
        return call(s -> s.getAccounts(GetAccountsRequest.newBuilder()
                .setOwnerId(owner).addAllAccountIds(accountIds).build()).getAccountsList());
    }

    public List<Account> listAccounts(String owner) {
        return call(s -> s.listAccounts(ListAccountsRequest.newBuilder().setOwnerId(owner).build()).getAccountsList());
    }

    public Account deposit(String owner, long accountId, long amountCents) {
        return call(s -> s.deposit(DepositRequest.newBuilder()
                .setOwnerId(owner).setAccountId(accountId).setAmountCents(amountCents).build()));
    }

    public TransferResult transfer(String owner, long from, long to, long amountCents, String idempotencyKey) {
        return call(s -> s.transfer(TransferRequest.newBuilder()
                .setOwnerId(owner)
                .setFromAccountId(from)
                .setToAccountId(to)
                .setAmountCents(amountCents)
                .setIdempotencyKey(idempotencyKey)
                .build()));
    }

    public ListTransfersResponse listTransfers(String owner, long accountId, int pageSize, Long afterId) {
        var req = ListTransfersRequest.newBuilder().setOwnerId(owner).setAccountId(accountId).setPageSize(pageSize);
        if (afterId != null) {
            req.setAfterId(afterId);
        }
        return call(s -> s.listTransfers(req.build()));
    }

    public Withdrawal withdraw(String owner, long accountId, long amountCents, String idempotencyKey) {
        return call(s -> s.withdraw(WithdrawRequest.newBuilder()
                .setOwnerId(owner)
                .setAccountId(accountId)
                .setAmountCents(amountCents)
                .setIdempotencyKey(idempotencyKey)
                .build()));
    }

    public Withdrawal getWithdrawal(String owner, long withdrawalId) {
        return call(s -> s.getWithdrawal(GetWithdrawalRequest.newBuilder()
                .setOwnerId(owner).setWithdrawalId(withdrawalId).build()));
    }

    private <T> T call(Function<LedgerGrpc.LedgerBlockingStub, T> rpc) {
        try {
            return rpc.apply(stub.withDeadlineAfter(2, TimeUnit.SECONDS));
        } catch (StatusRuntimeException e) {
            String description = e.getStatus().getDescription();
            throw switch (e.getStatus().getCode()) {
                case NOT_FOUND -> new ApiException(ErrorType.NOT_FOUND, description);
                case PERMISSION_DENIED -> new ApiException(ErrorType.FORBIDDEN, description);
                case FAILED_PRECONDITION -> new ApiException(ErrorType.BAD_REQUEST, description); // e.g. "insufficient funds"
                case INVALID_ARGUMENT -> new ApiException(ErrorType.BAD_REQUEST, description);
                case DEADLINE_EXCEEDED, UNAVAILABLE -> new ApiException(ErrorType.INTERNAL_ERROR, "ledger unavailable");
                default -> new ApiException(ErrorType.INTERNAL_ERROR, "ledger error");
            };
        }
    }
}
