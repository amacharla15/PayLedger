package com.akshith.payledger.bank;

// Retryable: the state machine retries this error 3 times with backoff.
public class BankUnavailable extends RuntimeException {

    public BankUnavailable(String message) {
        super(message);
    }
}
