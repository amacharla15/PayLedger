package com.akshith.payledger;

import org.springframework.boot.SpringApplication;

public class TestPayledgerApplication {

    public static void main(String[] args) {
        SpringApplication.from(PayledgerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
