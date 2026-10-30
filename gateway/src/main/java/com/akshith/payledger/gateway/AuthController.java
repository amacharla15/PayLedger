package com.akshith.payledger.gateway;

import com.akshith.payledger.gateway.AuthService.AuthPayload;
import java.security.Principal;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public String me(Principal user) {
        return user.getName();
    }

    @MutationMapping
    public AuthPayload register(@Argument String username, @Argument String password) {
        return auth.register(username, password);
    }

    @MutationMapping
    public AuthPayload login(@Argument String username, @Argument String password) {
        return auth.login(username, password);
    }

    @MutationMapping
    public AuthPayload refresh(@Argument String refreshToken) {
        return auth.refresh(refreshToken);
    }

    @MutationMapping
    public boolean logout(@Argument String refreshToken) {
        return auth.logout(refreshToken);
    }
}
