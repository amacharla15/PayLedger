package com.akshith.payledger.gateway;

import com.akshith.payledger.proto.Account;
import com.akshith.payledger.proto.GetAccountsRequest;
import com.akshith.payledger.proto.GetAccountsResponse;
import com.akshith.payledger.proto.LedgerGrpc;
import com.akshith.payledger.proto.ListTransfersRequest;
import com.akshith.payledger.proto.ListTransfersResponse;
import com.akshith.payledger.proto.Transfer;
import com.akshith.payledger.proto.TransferRequest;
import com.akshith.payledger.proto.TransferResult;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicInteger;

// In-process stand-in for the ledger: 20 transfers on account 1 between accounts 1..4, and a call counter.
class FakeLedger extends LedgerGrpc.LedgerImplBase {

    final AtomicInteger getAccountsCalls = new AtomicInteger();
    volatile String lastGetAccountsOwner;

    @Override
    public void listTransfers(ListTransfersRequest req, StreamObserver<ListTransfersResponse> out) {
        var page = ListTransfersResponse.newBuilder();
        for (int i = 1; i <= req.getPageSize(); i++) {
            page.addTransfers(Transfer.newBuilder().setId(i).setFromAccountId(1).setToAccountId(2 + i % 3)
                    .setAmountCents(i).setCreatedAt("2026-01-01T00:00:00Z"));
        }
        out.onNext(page.setNextCursor(1).build());
        out.onCompleted();
    }

    @Override
    public void getAccounts(GetAccountsRequest req, StreamObserver<GetAccountsResponse> out) {
        getAccountsCalls.incrementAndGet();
        lastGetAccountsOwner = req.getOwnerId();
        var response = GetAccountsResponse.newBuilder();
        for (long id : req.getAccountIdsList()) {
            response.addAccounts(Account.newBuilder().setId(id).setOwnerId("owner" + id));
        }
        out.onNext(response.build());
        out.onCompleted();
    }

    // Amount picks the failure: 1 insufficient funds, 2 not your account, 3 ledger down.
    @Override
    public void transfer(TransferRequest req, StreamObserver<TransferResult> out) {
        Status status = switch ((int) req.getAmountCents()) {
            case 1 -> Status.FAILED_PRECONDITION.withDescription("insufficient funds");
            case 2 -> Status.PERMISSION_DENIED.withDescription("account 1 does not belong to you");
            default -> Status.UNAVAILABLE;
        };
        out.onError(status.asRuntimeException());
    }
}
