package com.payflow.bank;

import com.payflow.bank.server.BankSimulatorServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
public class BankSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankSimulatorApplication.class, args);
    }

    @Bean
    public CommandLineRunner startTcpServer(BankSimulatorServer server) {
        return args -> {
            log.info("Starting Bank Simulator TCP server...");
            new Thread(() -> {
                try {
                    server.start();
                } catch (Exception e) {
                    log.error("Failed to start TCP server: {}", e.getMessage(), e);
                }
            }, "bank-tcp-server").start();
        };
    }
}
