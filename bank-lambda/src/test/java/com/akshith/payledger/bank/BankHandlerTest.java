package com.akshith.payledger.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BankHandlerTest {

    @Test
    void amount666IsAlwaysRejected() {
        var bank = new BankHandler(new Random(1));
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> bank.handleRequest(Map.of("withdrawalId", 1, "amountCents", 666)))
                    .isInstanceOf(BankRejected.class);
        }
    }

    @Test
    void outcomesFollowTheConfiguredRates() {
        var bank = new BankHandler(new Random(42));
        int unavailable = 0;
        int rejected = 0;
        int approved = 0;
        for (int i = 0; i < 10_000; i++) {
            try {
                bank.handleRequest(Map.of("withdrawalId", i, "amountCents", 500L));
                approved++;
            } catch (BankUnavailable e) {
                unavailable++;
            } catch (BankRejected e) {
                rejected++;
            }
        }
        assertThat(unavailable).isBetween(2_800, 3_200);
        assertThat(rejected).isBetween(800, 1_200);
        assertThat(approved).isBetween(5_800, 6_200);
    }
}
