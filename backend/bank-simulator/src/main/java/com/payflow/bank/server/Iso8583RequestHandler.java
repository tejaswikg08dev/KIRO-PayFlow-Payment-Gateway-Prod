package com.payflow.bank.server;

import com.payflow.bank.config.SimulatorConfig;
import com.payflow.bank.logic.ResponseGenerator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Netty channel handler that processes incoming ISO 8583 requests
 * and generates simulated bank responses.
 *
 * Simplified ISO 8583 format (for simulation):
 * Field layout: MTI(4) + PAN(19) + Amount(12) + RRN(12) = fixed-length message
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class Iso8583RequestHandler extends SimpleChannelInboundHandler<String> {

    private final ResponseGenerator responseGenerator;
    private final SimulatorConfig config;

    public Iso8583RequestHandler(ResponseGenerator responseGenerator, SimulatorConfig config) {
        this.responseGenerator = responseGenerator;
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String message) throws Exception {
        log.debug("Received ISO 8583 request: length={}", message.length());

        // Simulate network latency
        simulateLatency();

        // Parse simplified ISO 8583 fields
        String mti = extractField(message, 0, 4);
        String pan = extractField(message, 4, 23);
        String amount = extractField(message, 23, 35);
        String rrn = extractField(message, 35, 47);

        log.info("Processing: MTI={}, PAN={}****, Amount={}, RRN={}",
                mti, pan.substring(0, Math.min(6, pan.length())), amount, rrn);

        // Generate response
        String responseCode = responseGenerator.generateResponse(pan, amount);
        String responseMti = "0110"; // Response MTI

        // Build response message
        String response = responseMti + pan + amount + rrn + responseCode;

        ctx.writeAndFlush(response);
        log.info("Sent response: MTI={}, ResponseCode={}, RRN={}", responseMti, responseCode, rrn);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel error: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("New connection from: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Connection closed: {}", ctx.channel().remoteAddress());
    }

    private void simulateLatency() throws InterruptedException {
        int latency = ThreadLocalRandom.current().nextInt(
                config.getMinLatencyMs(), config.getMaxLatencyMs() + 1);
        Thread.sleep(latency);
    }

    private String extractField(String message, int start, int end) {
        if (message.length() >= end) {
            return message.substring(start, end).trim();
        }
        return message.substring(start).trim();
    }
}
