package com.akshith.payledger.gateway;

import com.akshith.payledger.proto.Withdrawal;
import java.security.Principal;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

// withdraw returns right away with status PENDING; clients poll withdrawal(id) until the saga settles or releases it.
@Controller
@PreAuthorize("isAuthenticated()")
public class WithdrawalController {

    public record WithdrawInput(long accountId, int amountCents, String idempotencyKey) {
    }

    private final LedgerClient ledger;
    private final RateLimiter rateLimiter;

    public WithdrawalController(LedgerClient ledger, RateLimiter rateLimiter) {
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
    }

    @MutationMapping
    public Withdrawal withdraw(@Argument WithdrawInput input, Principal user) {
        rateLimiter.check(user.getName());
        return ledger.withdraw(user.getName(), input.accountId(), input.amountCents(), input.idempotencyKey());
    }

    @QueryMapping
    public Withdrawal withdrawal(@Argument long id, Principal user) {
        return ledger.getWithdrawal(user.getName(), id);
    }
}
