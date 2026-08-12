package com.payflow.bank.server;

import com.payflow.bank.config.SimulatorConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Netty-based TCP server that simulates a bank/acquirer ISO 8583 interface.
 * Listens on a configurable port (default 9090) for incoming transaction requests.
 */
@Slf4j
@Component
public class BankSimulatorServer {

    private final SimulatorConfig config;
    private final BankChannelInitializer channelInitializer;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public BankSimulatorServer(SimulatorConfig config, BankChannelInitializer channelInitializer) {
        this.config = config;
        this.channelInitializer = channelInitializer;
    }

    /**
     * Start the Netty TCP server.
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(channelInitializer)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            int port = config.getTcpPort();
            ChannelFuture future = bootstrap.bind(port).sync();
            log.info("Bank Simulator TCP server started on port {}", port);
            log.info("Success rate: {}%, Latency: {}ms-{}ms",
                    config.getSuccessRatePercent(),
                    config.getMinLatencyMs(),
                    config.getMaxLatencyMs());

            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Bank Simulator TCP server...");
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
