package com.akshith.payledger.gateway;

import com.akshith.payledger.proto.Account;
import com.akshith.payledger.proto.ListTransfersResponse;
import com.akshith.payledger.proto.Transfer;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

@Controller
@PreAuthorize("isAuthenticated()")
public class TransferController {

    public record TransferInput(long fromAccountId, long toAccountId, int amountCents, String idempotencyKey) {
    }

    public record TransferPage(List<Transfer> items, Long nextCursor) {
    }

    private final LedgerClient ledger;
    private final RateLimiter rateLimiter;

    // Transfer.from and Transfer.to share one DataLoader, so a whole page costs a single GetAccounts call.
    // (@BatchMapping would register one loader per field, i.e. one call for "from" and another for "to".)
    public TransferController(LedgerClient ledger, RateLimiter rateLimiter, BatchLoaderRegistry registry) {
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
        registry.forTypePair(Long.class, Account.class).registerMappedBatchLoader((ids, env) -> {
            String owner = SecurityContextHolder.getContext().getAuthentication().getName();
            return Mono.fromCallable(() -> ledger.getAccounts(owner, ids).stream()
                    .collect(Collectors.toMap(Account::getId, Function.identity())));
        });
    }

    @QueryMapping
    public TransferPage transfers(@Argument long accountId, @Argument int first, @Argument Long after, Principal user) {
        ListTransfersResponse page = ledger.listTransfers(user.getName(), accountId, first, after);
        return new TransferPage(page.getTransfersList(), page.hasNextCursor() ? page.getNextCursor() : null);
    }

    @MutationMapping
    public Transfer transfer(@Argument TransferInput input, Principal user) {
        rateLimiter.check(user.getName());
        return ledger.transfer(user.getName(), input.fromAccountId(), input.toAccountId(), input.amountCents(),
                input.idempotencyKey()).getTransfer();
    }

    @SchemaMapping
    public CompletableFuture<Account> from(Transfer transfer, DataLoader<Long, Account> accounts) {
        return accounts.load(transfer.getFromAccountId());
    }

    @SchemaMapping
    public CompletableFuture<Account> to(Transfer transfer, DataLoader<Long, Account> accounts) {
        return accounts.load(transfer.getToAccountId());
    }
}
