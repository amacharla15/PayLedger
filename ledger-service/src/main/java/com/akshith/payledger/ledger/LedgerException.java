package com.akshith.payledger.ledger;

public class LedgerException extends RuntimeException {

    public enum Reason { NOT_FOUND, NOT_OWNER, INSUFFICIENT_FUNDS, INVALID, DISABLED }

    private final Reason reason;

    public LedgerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
