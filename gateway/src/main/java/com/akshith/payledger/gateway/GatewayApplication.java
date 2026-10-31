package com.akshith.payledger.gateway;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    // With dns:///ledger-headless:9090 the resolver returns every ledger pod and round_robin spreads calls across them.
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel ledgerChannel(@Value("${ledger.target}") String target) {
        return ManagedChannelBuilder.forTarget(target)
                .defaultLoadBalancingPolicy("round_robin")
                .usePlaintext()
                .build();
    }
}
