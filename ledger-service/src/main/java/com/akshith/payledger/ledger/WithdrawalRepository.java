package com.akshith.payledger.ledger;

import static com.akshith.payledger.ledger.LedgerException.Reason.INSUFFICIENT_FUNDS;
import static com.akshith.payledger.ledger.LedgerException.Reason.INVALID;
import static com.akshith.payledger.ledger.LedgerException.Reason.NOT_FOUND;

import com.akshith.payledger.proto.Account;
import com.akshith.payledger.proto.Withdrawal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// A withdrawal holds the money in the ledger, then a Step Functions saga asks the bank and tells the
// WithdrawalWorker to settle (money leaves) or release (money comes back).
@Repository
public class WithdrawalRepository {

    static final String SAGA_TOPIC = "sfn:withdrawal";

    public record SagaInput(long withdrawalId, long accountId, long amountCents) {
    }

    private static final RowMapper<Withdrawal> WITHDRAWAL = (rs, n) -> Withdrawal.newBuilder()
            .setId(rs.getLong("id"))
            .setAccountId(rs.getLong("account_id"))
            .setAmountCents(rs.getLong("amount_cents"))
            .setStatus(rs.getString("status"))
            .setCreatedAt(rs.getObject("created_at", OffsetDateTime.class).toString())
            .build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final LedgerRepository ledger;

    public WithdrawalRepository(JdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json, LedgerRepository ledger) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.ledger = ledger;
    }

    public Withdrawal withdraw(String owner, long accountId, long amount, String key) {
        LedgerRepository.requirePositive(amount);
        if (key.isBlank()) {
            throw new LedgerException(INVALID, "idempotency key is required");
        }
        try {
            return tx.execute(s -> withdrawInTx(owner, accountId, amount, key));
        } catch (DuplicateKeyException e) {
            return findByKey(owner, key).orElseThrow(() -> e);
        }
    }

    private Withdrawal withdrawInTx(String owner, long accountId, long amount, String key) {
        Optional<Withdrawal> existing = findByKey(owner, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        Account account = LedgerRepository.owned(ledger.account(accountId, true), owner);
        if (account.getBalanceCents() < amount) {
            throw new LedgerException(INSUFFICIENT_FUNDS, "insufficient funds");
        }

        jdbc.update("update accounts set balance_cents = balance_cents - ?, held_cents = held_cents + ? where id = ?",
                amount, amount, accountId);
        Withdrawal withdrawal = jdbc.queryForObject("""
                insert into withdrawals (account_id, amount_cents, idempotency_key)
                values (?, ?, ?) returning *""", WITHDRAWAL, accountId, amount, key);
        jdbc.update("insert into entries (account_id, withdrawal_id, kind, amount_cents) values (?, ?, 'hold', ?)",
                accountId, withdrawal.getId(), -amount);

        // msg_key becomes the Step Functions execution name. A re-sent outbox row reuses it, so the saga starts once.
        // The timestamp keeps names unique if the database is reset while the state machine keeps its history.
        String executionName = "withdrawal-" + withdrawal.getId() + "-" + System.currentTimeMillis();
        var input = new SagaInput(withdrawal.getId(), accountId, amount);
        jdbc.update("insert into outbox (topic, msg_key, payload, traceparent) values (?, ?, ?, ?)",
                SAGA_TOPIC, executionName, json.writeValueAsString(input), TraceContext.current());
        return withdrawal;
    }

    private Optional<Withdrawal> findByKey(String owner, String key) {
        return jdbc.query("select * from withdrawals where idempotency_key = ?", WITHDRAWAL, key).stream()
                .findFirst()
                .map(w -> {
                    LedgerRepository.owned(ledger.account(w.getAccountId(), false), owner);
                    return w.toBuilder().setDuplicate(true).build();
                });
    }

    public Withdrawal get(String owner, long id) {
        Withdrawal withdrawal = find(id, false);
        LedgerRepository.owned(ledger.account(withdrawal.getAccountId(), false), owner);
        return withdrawal;
    }

    public String settle(long id) {
        return finish(id, "SETTLED");
    }

    public String release(long id) {
        return finish(id, "RELEASED");
    }

    // A withdrawal that is already SETTLED or RELEASED is left alone, so a redelivered SQS message is a no-op.
    private String finish(long id, String status) {
        return tx.execute(s -> {
            Withdrawal withdrawal = find(id, true);
            if (!withdrawal.getStatus().equals("PENDING")) {
                return withdrawal.getStatus();
            }
            long amount = withdrawal.getAmountCents();
            long accountId = withdrawal.getAccountId();
            if (status.equals("SETTLED")) {
                jdbc.update("update accounts set held_cents = held_cents - ? where id = ?", amount, accountId);
            } else {
                jdbc.update("update accounts set held_cents = held_cents - ?, balance_cents = balance_cents + ? where id = ?",
                        amount, amount, accountId);
                jdbc.update("insert into entries (account_id, withdrawal_id, kind, amount_cents) values (?, ?, 'release', ?)",
                        accountId, id, amount);
            }
            jdbc.update("update withdrawals set status = ?, updated_at = now() where id = ?", status, id);
            return status;
        });
    }

    private Withdrawal find(long id, boolean forUpdate) {
        String sql = "select * from withdrawals where id = ?" + (forUpdate ? " for update" : "");
        return jdbc.query(sql, WITHDRAWAL, id).stream()
                .findFirst()
                .orElseThrow(() -> new LedgerException(NOT_FOUND, "withdrawal " + id + " not found"));
    }
}
