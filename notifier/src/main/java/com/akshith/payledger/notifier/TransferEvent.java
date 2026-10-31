package com.akshith.payledger.notifier;

public record TransferEvent(long transferId, long fromAccountId, long toAccountId, long amountCents,
                            String fromOwner, String toOwner) {
}
