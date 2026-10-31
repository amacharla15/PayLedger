package com.akshith.payledger.ledger;

import static com.akshith.payledger.ledger.LedgerException.Reason.INSUFFICIENT_FUNDS;
import static com.akshith.payledger.ledger.LedgerException.Reason.INVALID;
import static com.akshith.payledger.ledger.LedgerException.Reason.NOT_FOUND;
import static com.akshith.payledger.ledger.LedgerException.Reason.NOT_OWNER;

import com.akshith.payledger.proto.Account;
import com.akshith.payledger.proto.ListTransfersResponse;
import com.akshith.payledger.proto.Transfer;
import com.akshith.payledger.proto.TransferResult;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class LedgerRepository {

    private static final RowMapper<Account> ACCOUNT = (rs, n) -> Account.newBuilder()
            .setId(rs.getLong("id"))
            .setOwnerId(rs.getString("owner_id"))
            .setBalanceCents(rs.getLong("balance_cents"))
            .setHeldCents(rs.getLong("held_cents"))
            .build();

    private static final RowMapper<Transfer> TRANSFER = (rs, n) -> Transfer.newBuilder()
            .setId(rs.getLong("id"))
            .setFromAccountId(rs.getLong("from_account_id"))
            .setToAccountId(rs.getLong("to_account_id"))
            .setAmountCents(rs.getLong("amount_cents"))
            .setCreatedAt(rs.getObject("created_at", OffsetDateTime.class).toString())
            .build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public LedgerRepository(JdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    public Account openAccount(String owner) {
        return jdbc.queryForObject("insert into accounts (owner_id) values (?) returning *", ACCOUNT, owner);
    }

    public Account getAccount(String owner, long id) {
        return owned(account(id, false), owner);
    }

    public List<Account> getAccounts(String owner, Collection<Long> ids) {
        return jdbc.query("select * from accounts where id = any(?)", ACCOUNT, (Object) ids.toArray(Long[]::new))
                .stream()
                .map(a -> a.getOwnerId().equals(owner) ? a : a.toBuilder().setBalanceCents(0).setHeldCents(0).build())
                .toList();
    }

    public List<Account> listAccounts(String owner) {
        return jdbc.query("select * from accounts where owner_id = ? order by id", ACCOUNT, owner);
    }

    public Account deposit(String owner, long accountId, long amount) {
        requirePositive(amount);
        return tx.execute(s -> {
            owned(account(accountId, true), owner);
            jdbc.update("insert into entries (account_id, kind, amount_cents) values (?, 'deposit', ?)", accountId, amount);
            return jdbc.queryForObject("update accounts set balance_cents = balance_cents + ? where id = ? returning *",
                    ACCOUNT, amount, accountId);
        });
    }

    public TransferResult transfer(String owner, long fromId, long toId, long amount, String key) {
        requirePositive(amount);
        if (fromId == toId) {
            throw new LedgerException(INVALID, "cannot transfer to the same account");
        }
        if (key.isBlank()) {
            throw new LedgerException(INVALID, "idempotency key is required");
        }
        try {
            return tx.execute(s -> transferInTx(owner, fromId, toId, amount, key));
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key committed first and this one was rolled back.
            return findByKey(owner, key).orElseThrow(() -> e);
        }
    }

    private TransferResult transferInTx(String owner, long fromId, long toId, long amount, String key) {
        Optional<TransferResult> existing = findByKey(owner, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Lock both rows in ascending id order so A->B and B->A transfers cannot deadlock.
        Account first = account(Math.min(fromId, toId), true);
        Account second = account(Math.max(fromId, toId), true);
        Account from = first.getId() == fromId ? first : second;
        Account to = first.getId() == fromId ? second : first;
        owned(from, owner);
        if (from.getBalanceCents() < amount) {
            throw new LedgerException(INSUFFICIENT_FUNDS, "insufficient funds");
        }

        jdbc.update("update accounts set balance_cents = balance_cents - ? where id = ?", amount, fromId);
        jdbc.update("update accounts set balance_cents = balance_cents + ? where id = ?", amount, toId);
        Transfer transfer = jdbc.queryForObject("""
                insert into transfers (from_account_id, to_account_id, amount_cents, idempotency_key)
                values (?, ?, ?, ?) returning *""", TRANSFER, fromId, toId, amount, key);
        jdbc.update("insert into entries (account_id, transfer_id, kind, amount_cents) values (?, ?, 'transfer', ?), (?, ?, 'transfer', ?)",
                fromId, transfer.getId(), -amount, toId, transfer.getId(), amount);

        var event = new TransferEvent(transfer.getId(), fromId, toId, amount, from.getOwnerId(), to.getOwnerId());
        // The trace context rides along so the scheduled publisher can continue this request's trace.
        jdbc.update("insert into outbox (topic, msg_key, payload, traceparent) values ('transfers', ?, ?, ?)",
                String.valueOf(fromId), json.writeValueAsString(event), TraceContext.current());
        return TransferResult.newBuilder().setTransfer(transfer).build();
    }

    private Optional<TransferResult> findByKey(String owner, String key) {
        return jdbc.query("select * from transfers where idempotency_key = ?", TRANSFER, key).stream()
                .findFirst()
                .map(t -> {
                    owned(account(t.getFromAccountId(), false), owner);
                    return TransferResult.newBuilder().setTransfer(t).setDuplicate(true).build();
                });
    }

    public ListTransfersResponse listTransfers(String owner, long accountId, int pageSize, long afterId) {
        owned(account(accountId, false), owner);
        int size = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        // Fetch one extra row to know whether there is a next page.
        List<Transfer> rows = jdbc.query("""
                select * from transfers where (from_account_id = ? or to_account_id = ?) and id < ?
                order by id desc limit ?""", TRANSFER, accountId, accountId, afterId, size + 1);
        var page = ListTransfersResponse.newBuilder().addAllTransfers(rows.subList(0, Math.min(size, rows.size())));
        if (rows.size() > size) {
            page.setNextCursor(rows.get(size - 1).getId());
        }
        return page.build();
    }

    public Account account(long id, boolean forUpdate) {
        String sql = "select * from accounts where id = ?" + (forUpdate ? " for update" : "");
        return jdbc.query(sql, ACCOUNT, id).stream()
                .findFirst()
                .orElseThrow(() -> new LedgerException(NOT_FOUND, "account " + id + " not found"));
    }

    static Account owned(Account account, String owner) {
        if (!account.getOwnerId().equals(owner)) {
            throw new LedgerException(NOT_OWNER, "account " + account.getId() + " does not belong to you");
        }
        return account;
    }

    static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new LedgerException(INVALID, "amount must be positive");
        }
    }
}
