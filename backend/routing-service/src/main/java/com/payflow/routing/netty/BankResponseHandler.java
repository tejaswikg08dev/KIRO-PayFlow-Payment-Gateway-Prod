package com.payflow.routing.netty;

import com.payflow.routing.iso8583.Iso8583Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Netty handler that receives ISO 8583 response messages from the bank
 * and completes the associated CompletableFuture.
 */
public class BankResponseHandler extends SimpleChannelInboundHandler<Iso8583Message> {

    private static final Logger log = LoggerFactory.getLogger(BankResponseHandler.class);

    private CompletableFuture<Iso8583Message> responseFuture;

    public BankResponseHandler() {
    }

    /**
     * Sets the CompletableFuture that will be completed when a response is received.
     */
    public void setResponseFuture(CompletableFuture<Iso8583Message> responseFuture) {
        this.responseFuture = responseFuture;
    }

    public CompletableFuture<Iso8583Message> getResponseFuture() {
        return responseFuture;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Iso8583Message response) {
        log.debug("Received bank response: MTI={}, fields={}",
                response.getMti(), response.getFields().keySet());

        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.complete(response);
        } else {
            log.warn("Received unexpected response (no pending future): MTI={}", response.getMti());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error in bank response handler: {}", cause.getMessage(), cause);
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("Bank connection closed");
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(
                    new RuntimeException("Connection closed before response received"));
        }
    }
}
