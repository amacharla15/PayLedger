package com.akshith.payledger.bank;

import java.util.Map;
import java.util.Random;

// A fake bank for the withdrawal saga. Lambda calls handleRequest with the state machine input as a Map
// (handler: com.akshith.payledger.bank.BankHandler::handleRequest), so no Lambda library is needed.
// A thrown exception fails the task; Step Functions sees its class name as the error name and decides
// whether to retry (BankUnavailable) or release the hold (anything else).
public class BankHandler {

    private final Random random;

    public BankHandler() {
        this(new Random());
    }

    BankHandler(Random random) {
        this.random = random;
    }

    public Map<String, Object> handleRequest(Map<String, Object> input) {
        long withdrawalId = ((Number) input.get("withdrawalId")).longValue();
        long amount = ((Number) input.get("amountCents")).longValue();
        if (amount == 666) {
            throw new BankRejected("withdrawal " + withdrawalId + ": amount 666 is always rejected");
        }
        double roll = random.nextDouble();
        if (roll < 0.3) {
            throw new BankUnavailable("withdrawal " + withdrawalId + ": bank did not answer");
        }
        if (roll < 0.4) {
            throw new BankRejected("withdrawal " + withdrawalId + ": bank declined");
        }
        return Map.of("withdrawalId", withdrawalId, "approved", true);
    }
}
