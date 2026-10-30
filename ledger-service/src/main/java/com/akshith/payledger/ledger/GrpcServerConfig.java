package com.akshith.payledger.ledger;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcServerConfig {

    @Bean(destroyMethod = "shutdown")
    Server grpcServer(LedgerGrpcService service, @Value("${grpc.port}") int port) throws IOException {
        return NettyServerBuilder.forPort(port)
                .addService(service)
                // Closing connections periodically makes clients re-resolve DNS and pick up new ledger pods.
                .maxConnectionAge(30, TimeUnit.SECONDS)
                .maxConnectionAgeGrace(5, TimeUnit.SECONDS)
                .build()
                .start();
    }
}
