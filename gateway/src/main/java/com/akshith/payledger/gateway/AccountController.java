package com.akshith.payledger.gateway;

import com.akshith.payledger.proto.Account;
import java.security.Principal;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
@PreAuthorize("isAuthenticated()")
public class AccountController {

    private final LedgerClient ledger;
    private final RateLimiter rateLimiter;

    public AccountController(LedgerClient ledger, RateLimiter rateLimiter) {
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
    }

    @QueryMapping
    public List<Account> myAccounts(Principal user) {
        return ledger.listAccounts(user.getName());
    }

    @QueryMapping
    public Account account(@Argument long id, Principal user) {
        return ledger.getAccount(user.getName(), id);
    }

    @MutationMapping
    public Account openAccount(Principal user) {
        rateLimiter.check(user.getName());
        return ledger.openAccount(user.getName());
    }

    @MutationMapping
    public Account deposit(@Argument long accountId, @Argument int amountCents, Principal user) {
        rateLimiter.check(user.getName());
        return ledger.deposit(user.getName(), accountId, amountCents);
    }
}
