package com.payflow.routing.netty;

import com.payflow.routing.iso8583.Iso8583Message;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Netty-based TCP client for communicating with the bank simulator.
 * Sends ISO 8583 messages and receives responses asynchronously via CompletableFuture.
 */
@Component
public class BankNettyClient {

    private static final Logger log = LoggerFactory.getLogger(BankNettyClient.class);

    private final EventLoopGroup workerGroup;
    private final String bankHost;
    private final int bankPort;
    private final int connectTimeoutMs;
    private final int responseTimeoutSeconds;

    public BankNettyClient(
            EventLoopGroup workerGroup,
            @Value("${bank.simulator.host:localhost}") String bankHost,
            @Value("${bank.simulator.port:9090}") int bankPort,
            @Value("${bank.simulator.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${bank.simulator.response-timeout-seconds:30}") int responseTimeoutSeconds) {
        this.workerGroup = workerGroup;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.connectTimeoutMs = connectTimeoutMs;
        this.responseTimeoutSeconds = responseTimeoutSeconds;
    }

    /**
     * Sends an ISO 8583 message to the bank and returns the response asynchronously.
     *
     * @param request ISO 8583 request message
     * @return CompletableFuture containing the bank's response
     */
    public CompletableFuture<Iso8583Message> sendMessage(Iso8583Message request) {
        CompletableFuture<Iso8583Message> responseFuture = new CompletableFuture<>();

        BankChannelInitializer initializer = new BankChannelInitializer(responseTimeoutSeconds);

        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(initializer);

        log.debug("Connecting to bank at {}:{}", bankHost, bankPort);

        ChannelFuture connectFuture = bootstrap.connect(bankHost, bankPort);

        connectFuture.addListener(future -> {
            if (future.isSuccess()) {
                Channel channel = connectFuture.channel();
                BankResponseHandler handler = initializer.getResponseHandler();
                handler.setResponseFuture(responseFuture);

                log.debug("Connected to bank, sending message: MTI={}", request.getMti());
                channel.writeAndFlush(request).addListener(writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        log.error("Failed to write message to bank: {}",
                                writeFuture.cause().getMessage());
                        responseFuture.completeExceptionally(writeFuture.cause());
                        channel.close();
                    }
                });

                // Close channel when response is received
                responseFuture.whenComplete((response, ex) -> channel.close());
            } else {
                log.error("Failed to connect to bank at {}:{}: {}",
                        bankHost, bankPort, future.cause().getMessage());
                responseFuture.completeExceptionally(future.cause());
            }
        });

        // Apply timeout
        return responseFuture.orTimeout(responseTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException) {
                        log.error("Bank response timeout after {} seconds", responseTimeoutSeconds);
                    }
                    throw new RuntimeException("Bank communication failed: " + ex.getMessage(), ex);
                });
    }

    /**
     * Sends a message and blocks until response is received or timeout expires.
     *
     * @param request ISO 8583 request message
     * @return Bank response message
     * @throws RuntimeException if communication fails or times out
     */
    public Iso8583Message sendMessageSync(Iso8583Message request) {
        try {
            return sendMessage(request).get(responseTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Synchronous bank communication failed: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down bank Netty client");
        if (workerGroup != null && !workerGroup.isShutdown()) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }
}
