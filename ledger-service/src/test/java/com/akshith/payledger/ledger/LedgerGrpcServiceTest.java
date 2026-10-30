package com.akshith.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akshith.payledger.proto.DepositRequest;
import com.akshith.payledger.proto.GetAccountRequest;
import com.akshith.payledger.proto.GetAccountsRequest;
import com.akshith.payledger.proto.LedgerGrpc;
import com.akshith.payledger.proto.ListTransfersRequest;
import com.akshith.payledger.proto.ListTransfersResponse;
import com.akshith.payledger.proto.OpenAccountRequest;
import com.akshith.payledger.proto.TransferRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "grpc.port=0")
@Import(TestcontainersConfig.class)
class LedgerGrpcServiceTest {

    @Autowired
    Server server;

    ManagedChannel channel;
    LedgerGrpc.LedgerBlockingStub ledger;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        ledger = LedgerGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    @Test
    void mapsLedgerErrorsToStatusCodes() {
        long a = open("erin", 100);
        long b = open("frank", 0);

        assertStatus(() -> ledger.getAccount(GetAccountRequest.newBuilder().setOwnerId("erin").setAccountId(-1).build()),
                Status.Code.NOT_FOUND);
        assertStatus(() -> ledger.getAccount(GetAccountRequest.newBuilder().setOwnerId("frank").setAccountId(a).build()),
                Status.Code.PERMISSION_DENIED);
        assertStatus(() -> transfer("erin", a, b, 500), Status.Code.FAILED_PRECONDITION);
        assertStatus(() -> transfer("erin", a, b, 0), Status.Code.INVALID_ARGUMENT);
        assertStatus(() -> transfer("erin", a, a, 10), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void getAccountsHidesBalancesOfOtherOwners() {
        long mine = open("gina", 100);
        long theirs = open("hank", 200);

        var accounts = ledger.getAccounts(GetAccountsRequest.newBuilder()
                .setOwnerId("gina").addAllAccountIds(List.of(mine, theirs)).build()).getAccountsList();

        assertThat(accounts).hasSize(2);
        assertThat(accounts).filteredOn(x -> x.getId() == mine).first().extracting("balanceCents").isEqualTo(100L);
        assertThat(accounts).filteredOn(x -> x.getId() == theirs).first().extracting("balanceCents").isEqualTo(0L);
    }

    @Test
    void listTransfersPagesNewestFirst() {
        long a = open("ivy", 1000);
        long b = open("jack", 0);
        for (int i = 1; i <= 5; i++) {
            transfer("ivy", a, b, i);
        }

        ListTransfersResponse first = page(a, null);
        ListTransfersResponse second = page(a, first.getNextCursor());
        ListTransfersResponse third = page(a, second.getNextCursor());

        assertThat(first.getTransfersList()).extracting("amountCents").containsExactly(5L, 4L);
        assertThat(second.getTransfersList()).extracting("amountCents").containsExactly(3L, 2L);
        assertThat(third.getTransfersList()).extracting("amountCents").containsExactly(1L);
        assertThat(third.hasNextCursor()).isFalse();
    }

    private ListTransfersResponse page(long accountId, Long after) {
        var req = ListTransfersRequest.newBuilder().setOwnerId("ivy").setAccountId(accountId).setPageSize(2);
        if (after != null) {
            req.setAfterId(after);
        }
        return ledger.listTransfers(req.build());
    }

    private long open(String owner, long cents) {
        long id = ledger.openAccount(OpenAccountRequest.newBuilder().setOwnerId(owner).build()).getId();
        if (cents > 0) {
            ledger.deposit(DepositRequest.newBuilder().setOwnerId(owner).setAccountId(id).setAmountCents(cents).build());
        }
        return id;
    }

    private void transfer(String owner, long from, long to, long cents) {
        ledger.transfer(TransferRequest.newBuilder().setOwnerId(owner).setFromAccountId(from).setToAccountId(to)
                .setAmountCents(cents).setIdempotencyKey(UUID.randomUUID().toString()).build());
    }

    private static void assertStatus(Executable call, Status.Code code) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
    }
}
