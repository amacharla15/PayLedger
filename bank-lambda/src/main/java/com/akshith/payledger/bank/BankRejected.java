package com.akshith.payledger.bank;

// Final: the state machine releases the hold.
public class BankRejected extends RuntimeException {

    public BankRejected(String message) {
        super(message);
    }
}
