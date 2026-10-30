package com.akshith.payledger.gateway;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:8.10").withExposedPorts(6379);
    }

    @Bean
    FakeLedger fakeLedger() {
        return new FakeLedger();
    }

    @Bean(destroyMethod = "shutdownNow")
    Server fakeLedgerServer(FakeLedger fakeLedger) throws IOException {
        return InProcessServerBuilder.forName("fake-ledger").addService(fakeLedger).build().start();
    }

    @Bean(destroyMethod = "shutdownNow")
    @Primary
    ManagedChannel fakeLedgerChannel(Server fakeLedgerServer) {
        return InProcessChannelBuilder.forName("fake-ledger").build();
    }
}
