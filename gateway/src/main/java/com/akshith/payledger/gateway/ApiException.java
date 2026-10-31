package com.akshith.payledger.gateway;

import org.springframework.graphql.execution.ErrorType;

public class ApiException extends RuntimeException {

    private final ErrorType type;

    public ApiException(ErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public ErrorType type() {
        return type;
    }
}
