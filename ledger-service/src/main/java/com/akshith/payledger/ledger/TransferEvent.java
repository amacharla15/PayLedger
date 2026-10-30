package com.akshith.payledger.ledger;

public record TransferEvent(long transferId, long fromAccountId, long toAccountId, long amountCents,
                            String fromOwner, String toOwner) {
}
