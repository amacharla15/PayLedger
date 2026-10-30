package com.akshith.payledger.ledger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akshith.payledger.proto.TransferResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "grpc.port=0")
@Import(TestcontainersConfig.class)
class LedgerRepositoryTest {

    @Autowired
    LedgerRepository ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void transferMovesMoney() {
        long a = account("alice", 1000);
        long b = account("bob", 0);

        ledger.transfer("alice", a, b, 300, key());

        assertThat(balance(a)).isEqualTo(700);
        assertThat(balance(b)).isEqualTo(300);
        assertThat(entrySum(a)).isEqualTo(700);
        assertThat(entrySum(b)).isEqualTo(300);
    }

    @Test
    void overdraftIsRejectedAndBalancesUnchanged() {
        long a = account("alice", 100);
        long b = account("bob", 50);

        assertThatThrownBy(() -> ledger.transfer("alice", a, b, 101, key()))
                .isInstanceOf(LedgerException.class)
                .hasMessage("insufficient funds");

        assertThat(balance(a)).isEqualTo(100);
        assertThat(balance(b)).isEqualTo(50);
        assertThat(jdbc.queryForObject("select count(*) from transfers where from_account_id = ?", Long.class, a)).isZero();
    }

    @Test
    void sameIdempotencyKeyTwiceCreatesOneTransfer() {
        long a = account("alice", 1000);
        long b = account("bob", 0);
        String key = key();

        TransferResult first = ledger.transfer("alice", a, b, 250, key);
        TransferResult second = ledger.transfer("alice", a, b, 250, key);

        assertThat(second.getTransfer().getId()).isEqualTo(first.getTransfer().getId());
        assertThat(first.getDuplicate()).isFalse();
        assertThat(second.getDuplicate()).isTrue();
        assertThat(balance(a)).isEqualTo(750);
        assertThat(jdbc.queryForObject("select count(*) from entries where transfer_id = ?", Long.class,
                first.getTransfer().getId())).isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneTransfer() throws Exception {
        long a = account("alice", 1000);
        long b = account("bob", 0);
        String key = key();

        List<TransferResult> results = runConcurrently(10, i -> ledger.transfer("alice", a, b, 100, key));

        assertThat(results).extracting(r -> r.getTransfer().getId()).containsOnly(results.getFirst().getTransfer().getId());
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void concurrentTransfersInBothDirectionsDoNotDeadlock() throws Exception {
        long a = account("carol", 10_000);
        long b = account("carol", 10_000);

        runConcurrently(50, i -> i % 2 == 0
                ? ledger.transfer("carol", a, b, 7, key())
                : ledger.transfer("carol", b, a, 7, key()));

        assertThat(balance(a) + balance(b)).isEqualTo(20_000);
        assertThat(balance(a)).isEqualTo(10_000);
        assertThat(entrySum(a)).isEqualTo(balance(a));
        assertThat(entrySum(b)).isEqualTo(balance(b));
    }

    @Test
    void everyBalanceEqualsSumOfEntriesAfterRandomOperations() {
        Random random = new Random(42);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(account("dave", 0));
        }
        int rejected = 0;
        for (int i = 0; i < 300; i++) {
            long from = ids.get(random.nextInt(ids.size()));
            if (random.nextInt(3) == 0) {
                ledger.deposit("dave", from, 1 + random.nextInt(500));
                continue;
            }
            long to = ids.get(random.nextInt(ids.size()));
            try {
                ledger.transfer("dave", from, to, 1 + random.nextInt(300), key());
            } catch (LedgerException e) {
                rejected++;
            }
        }

        assertThat(rejected).isPositive();
        List<Long> mismatched = jdbc.queryForList("""
                select a.id from accounts a left join entries e on e.account_id = a.id
                group by a.id, a.balance_cents
                having a.balance_cents <> coalesce(sum(e.amount_cents), 0)""", Long.class);
        assertThat(mismatched).isEmpty();
    }

    interface Call<T> {
        T run(int i) throws Exception;
    }

    private static <T> List<T> runConcurrently(int count, Call<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                return call.run(n);
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private long account(String owner, long cents) {
        long id = ledger.openAccount(owner).getId();
        if (cents > 0) {
            ledger.deposit(owner, id, cents);
        }
        return id;
    }

    private long balance(long id) {
        return jdbc.queryForObject("select balance_cents from accounts where id = ?", Long.class, id);
    }

    private long entrySum(long id) {
        return jdbc.queryForObject("select coalesce(sum(amount_cents), 0) from entries where account_id = ?", Long.class, id);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
