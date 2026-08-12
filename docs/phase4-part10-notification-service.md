# Phase 4 Part 10: Notification Service Implementation

## Overview

The Notification Service consumes Kafka events and sends email/SMS notifications to end customers. It uses AWS SES for emails and AWS SNS for SMS, with HTML templates for professional receipts.

## Architecture

```
┌─────────────┐     ┌──────────────────────────────────┐     ┌──────────────┐
│   KAFKA     │     │     NOTIFICATION SERVICE          │     │   AWS SES    │
│             │     │                                    │────▶│   (Email)    │
│ payment.*   │────▶│ Consumer → Template Engine →      │     └──────────────┘
│ refund.*    │     │         → Channel Router →        │
│ settlement.*│     │         → Send                    │     ┌──────────────┐
└─────────────┘     └──────────────────────────────────┘────▶│   AWS SNS    │
                                                              │   (SMS)      │
                                                              └──────────────┘
```

## Kafka Consumer

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
        topics = {"payment.captured", "payment.failed", "refund.completed"},
        groupId = "notification-service"
    )
    public void handlePaymentEvent(@Payload PaymentEvent event,
                                    @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Notification event received: topic={}, orderId={}",
            topic, event.orderId());

        switch (topic) {
            case "payment.captured" ->
                notificationService.sendPaymentConfirmation(event);
            case "payment.failed" ->
                notificationService.sendPaymentFailure(event);
            case "refund.completed" ->
                notificationService.sendRefundConfirmation(event);
            default -> log.warn("Unhandled topic: {}", topic);
        }
    }
}
```

## Notification Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final EmailService emailService;
    private final SmsService smsService;
    private final TemplateEngine templateEngine;

    public void sendPaymentConfirmation(PaymentEvent event) {
        // Send email if available
        if (event.customerEmail() != null) {
            String html = templateEngine.renderPaymentSuccess(event);
            emailService.sendEmail(
                event.customerEmail(),
                "Payment Successful - Order #" + event.orderId().substring(0, 8),
                html
            );
        }

        // Send SMS if phone available
        if (event.customerPhone() != null) {
            String message = String.format(
                "Payment of Rs.%.2f successful for order %s. " +
                "Ref: %s. Thank you!",
                event.amount() / 100.0,
                event.orderId().substring(0, 8),
                event.rrn()
            );
            smsService.sendSms(event.customerPhone(), message);
        }
    }

    public void sendPaymentFailure(PaymentEvent event) {
        if (event.customerEmail() != null) {
            String html = templateEngine.renderPaymentFailed(event);
            emailService.sendEmail(
                event.customerEmail(),
                "Payment Failed - Order #" + event.orderId().substring(0, 8),
                html
            );
        }
    }

    public void sendRefundConfirmation(PaymentEvent event) {
        if (event.customerEmail() != null) {
            String html = templateEngine.renderRefundSuccess(event);
            emailService.sendEmail(
                event.customerEmail(),
                "Refund Processed - Rs." +
                    String.format("%.2f", event.amount() / 100.0),
                html
            );
        }

        if (event.customerPhone() != null) {
            String message = String.format(
                "Refund of Rs.%.2f processed. " +
                "Amount will be credited within 5-7 business days.",
                event.amount() / 100.0
            );
            smsService.sendSms(event.customerPhone(), message);
        }
    }
}
```

## AWS SES Email Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final SesClient sesClient;

    @Value("${notification.email.from}")
    private String fromEmail;

    @Value("${notification.email.from-name}")
    private String fromName;

    public void sendEmail(String to, String subject, String htmlBody) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                .source(fromName + " <" + fromEmail + ">")
                .destination(Destination.builder()
                    .toAddresses(to)
                    .build())
                .message(Message.builder()
                    .subject(Content.builder().data(subject).build())
                    .body(Body.builder()
                        .html(Content.builder()
                            .data(htmlBody)
                            .charset("UTF-8")
                            .build())
                        .build())
                    .build())
                .build();

            sesClient.sendEmail(request);
            log.info("Email sent to: {}, subject: {}", to, subject);

        } catch (SesException e) {
            log.error("Failed to send email to: {}", to, e);
            // Don't throw - notification failure shouldn't break flow
        }
    }
}
```

## AWS SNS SMS Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsService {

    private final SnsClient snsClient;

    public void sendSms(String phoneNumber, String message) {
        try {
            // Ensure E.164 format (+91 for India)
            String formattedNumber = formatPhoneNumber(phoneNumber);

            PublishRequest request = PublishRequest.builder()
                .phoneNumber(formattedNumber)
                .message(message)
                .messageAttributes(Map.of(
                    "AWS.SNS.SMS.SMSType",
                    MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("Transactional")
                        .build()
                ))
                .build();

            snsClient.publish(request);
            log.info("SMS sent to: {}", maskPhone(phoneNumber));

        } catch (SnsException e) {
            log.error("Failed to send SMS to: {}", maskPhone(phoneNumber), e);
        }
    }

    private String formatPhoneNumber(String phone) {
        phone = phone.replaceAll("[^0-9+]", "");
        if (!phone.startsWith("+")) {
            if (phone.startsWith("91") && phone.length() == 12) {
                phone = "+" + phone;
            } else if (phone.length() == 10) {
                phone = "+91" + phone;
            }
        }
        return phone;
    }

    private String maskPhone(String phone) {
        if (phone.length() > 4) {
            return "****" + phone.substring(phone.length() - 4);
        }
        return "****";
    }
}
```

## HTML Email Templates

```java
@Component
public class TemplateEngine {

    public String renderPaymentSuccess(PaymentEvent event) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif;
                           background: #f5f5f5; margin: 0; padding: 20px; }
                    .container { max-width: 600px; margin: 0 auto;
                                 background: white; border-radius: 8px;
                                 overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                    .header { background: #10b981; padding: 30px; text-align: center; }
                    .header h1 { color: white; margin: 0; font-size: 24px; }
                    .body { padding: 30px; }
                    .amount { font-size: 36px; font-weight: bold; color: #1f2937;
                              text-align: center; margin: 20px 0; }
                    .details { background: #f9fafb; border-radius: 6px;
                               padding: 20px; margin: 20px 0; }
                    .detail-row { display: flex; justify-content: space-between;
                                  padding: 8px 0; border-bottom: 1px solid #e5e7eb; }
                    .detail-row:last-child { border-bottom: none; }
                    .footer { background: #f9fafb; padding: 20px; text-align: center;
                              font-size: 12px; color: #6b7280; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>✓ Payment Successful</h1>
                    </div>
                    <div class="body">
                        <div class="amount">₹%s</div>
                        <div class="details">
                            <div class="detail-row">
                                <span>Order ID</span>
                                <span>%s</span>
                            </div>
                            <div class="detail-row">
                                <span>Reference</span>
                                <span>%s</span>
                            </div>
                            <div class="detail-row">
                                <span>Payment Method</span>
                                <span>%s</span>
                            </div>
                            <div class="detail-row">
                                <span>Date</span>
                                <span>%s</span>
                            </div>
                        </div>
                    </div>
                    <div class="footer">
                        <p>This is an automated receipt from PayFlow.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(
                String.format("%.2f", event.amount() / 100.0),
                event.orderId().substring(0, 8),
                event.rrn(),
                event.paymentMethod(),
                event.timestamp()
            );
    }

    public String renderPaymentFailed(PaymentEvent event) {
        // Similar template with red header and failure message
        return """
            <!DOCTYPE html>
            <html>
            <head><style>/* ... */</style></head>
            <body>
                <div class="container">
                    <div class="header" style="background: #ef4444;">
                        <h1>✗ Payment Failed</h1>
                    </div>
                    <div class="body">
                        <p>Your payment of ₹%s could not be processed.</p>
                        <p>Order: %s</p>
                        <p>Please try again or use a different payment method.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(
                String.format("%.2f", event.amount() / 100.0),
                event.orderId().substring(0, 8)
            );
    }

    public String renderRefundSuccess(PaymentEvent event) {
        return """
            <!DOCTYPE html>
            <html>
            <head><style>/* ... */</style></head>
            <body>
                <div class="container">
                    <div class="header" style="background: #3b82f6;">
                        <h1>↩ Refund Processed</h1>
                    </div>
                    <div class="body">
                        <div class="amount">₹%s</div>
                        <p>Your refund has been processed successfully.</p>
                        <p>The amount will be credited to your account
                           within 5-7 business days.</p>
                        <div class="details">
                            <div class="detail-row">
                                <span>Original Order</span>
                                <span>%s</span>
                            </div>
                            <div class="detail-row">
                                <span>Refund Reference</span>
                                <span>%s</span>
                            </div>
                        </div>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(
                String.format("%.2f", event.amount() / 100.0),
                event.orderId().substring(0, 8),
                event.rrn()
            );
    }
}
```

## Configuration

```yaml
spring:
  application:
    name: notification-service
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      group-id: notification-service
      auto-offset-reset: earliest

notification:
  email:
    from: noreply@payflow.io
    from-name: PayFlow Payments
    enabled: ${EMAIL_ENABLED:true}
  sms:
    enabled: ${SMS_ENABLED:true}

aws:
  region: ${AWS_REGION:ap-south-1}
  ses:
    endpoint: ${SES_ENDPOINT:}
  sns:
    endpoint: ${SNS_ENDPOINT:}

server:
  port: 8088
```

## Notification Types Summary

| Event | Email | SMS | Template |
|-------|-------|-----|----------|
| payment.captured | ✅ | ✅ | Green success receipt |
| payment.failed | ✅ | ❌ | Red failure notice |
| refund.completed | ✅ | ✅ | Blue refund confirmation |
| settlement.completed | ✅ (merchant) | ❌ | Settlement summary |
