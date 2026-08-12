# Phase 4 · Part 13 — Notification Service

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Event-Driven Services |
| **Part** | 13 — Notification Service (Email + SMS) |
| **Previous** | [Part 12 — Webhook Service](./phase4-part12-webhook-service.md) |
| **Next** | [Part 14 — Docker & Containerization](./phase4-part14-docker.md) |
| **Time** | ~2 hours |
| **Difficulty** | ★★★☆☆ (Intermediate) |
| **Prerequisites** | Kafka (Part 6), AWS basics, Thymeleaf templates |
| **What You'll Build** | Email and SMS notification system using AWS SES/SNS |
| **Git Commit** | `feat(notification): add email/SMS notifications with templates` |

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [KafkaNotificationConsumer](#2-kafkanotificationconsumer)
3. [EmailService — AWS SES Integration](#3-emailservice)
4. [SmsService — AWS SNS Integration](#4-smsservice)
5. [TemplateService — Thymeleaf HTML Templates](#5-templateservice)
6. [Template Examples](#6-template-examples)
7. [Testing with LocalStack](#7-testing-with-localstack)
8. [What You Learned](#8-what-you-learned)
9. [Common Errors & Fixes](#9-common-errors--fixes)
10. [Git Commit](#10-git-commit)

---

## What You'll Learn

- How to consume Kafka notification events and route to email or SMS
- How to integrate with AWS SES for transactional emails
- How to send SMS messages via AWS SNS
- How to use Thymeleaf templates for professional HTML emails
- How to test AWS services locally using LocalStack
- How to handle notification failures gracefully

---

## 1. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    NOTIFICATION SERVICE ARCHITECTURE                      │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Kafka Topics                 Notification Service          AWS Services  │
│  ────────────                 ─────────────────────         ────────────  │
│                                                                           │
│  notification.email ──┐                                                  │
│                       ├──→ KafkaNotificationConsumer                     │
│  notification.sms  ───┘            │                                     │
│                                    │                                     │
│                            ┌───────┴───────┐                             │
│                            │               │                             │
│                            ▼               ▼                             │
│                     ┌──────────┐    ┌──────────┐                        │
│                     │  Email   │    │   SMS    │                         │
│                     │ Service  │    │ Service  │                         │
│                     └────┬─────┘    └────┬─────┘                        │
│                          │               │                               │
│                          ▼               ▼                               │
│                   ┌────────────┐  ┌────────────┐                        │
│                   │ Template   │  │            │                         │
│                   │ Service    │  │  AWS SNS   │ ──→ SMS to phone       │
│                   │ (Thymeleaf)│  │            │                         │
│                   └────┬───────┘  └────────────┘                        │
│                        │                                                 │
│                        ▼                                                 │
│                 ┌────────────┐                                           │
│                 │  AWS SES   │ ──→ Email to inbox                       │
│                 │            │                                           │
│                 └────────────┘                                           │
│                                                                           │
│  Event Types:                                                            │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ notification.email:                                               │   │
│  │   • payment-success → "Your payment of ₹5,000 was successful"    │   │
│  │   • refund-processed → "Refund of ₹2,000 initiated"             │   │
│  │   • payment-failed → "Payment could not be processed"            │   │
│  │                                                                   │   │
│  │ notification.sms:                                                 │   │
│  │   • payment-otp → "Your OTP is 847291"                          │   │
│  │   • payment-alert → "₹5,000 debited from card **4242"           │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 2. KafkaNotificationConsumer

```java
package com.payflow.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.notification.model.EmailNotification;
import com.payflow.notification.model.SmsNotification;
import com.payflow.notification.service.EmailService;
import com.payflow.notification.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes notification events from Kafka and dispatches to email/SMS.
 *
 * WHY separate topics (not one notification topic):
 * - Different processing guarantees (email can be async, OTP SMS must be instant)
 * - Different consumer scaling (email is high-volume, SMS is lower)
 * - Easier monitoring (alert if SMS queue grows)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KafkaNotificationConsumer {

    private final EmailService emailService;
    private final SmsService smsService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "notification.email",
            groupId = "notification-email-group",
            concurrency = "3"  // WHY 3: Email sending is I/O-bound, 3 threads maximize throughput
    )
    public void onEmailNotification(String message) {
        try {
            // WHY: Deserialize Kafka message into strongly-typed object
            EmailNotification notification = objectMapper.readValue(message, EmailNotification.class);

            log.info("📧 Processing email notification: to={}, template={}",
                    notification.getRecipientEmail(), notification.getTemplateName());

            emailService.send(notification);

        } catch (Exception e) {
            // WHY: Log error but don't rethrow — prevent consumer from stopping
            // Failed notifications are logged for ops visibility
            log.error("❌ Failed to process email notification: {}", message, e);
        }
    }

    @KafkaListener(
            topics = "notification.sms",
            groupId = "notification-sms-group",
            concurrency = "2"  // WHY 2: SMS is lower volume than email
    )
    public void onSmsNotification(String message) {
        try {
            SmsNotification notification = objectMapper.readValue(message, SmsNotification.class);

            log.info("📱 Processing SMS notification: to={}, type={}",
                    maskPhone(notification.getPhoneNumber()), notification.getType());

            smsService.send(notification);

        } catch (Exception e) {
            log.error("❌ Failed to process SMS notification: {}", message, e);
        }
    }

    // WHY: Don't log full phone numbers (PII protection)
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "***" + phone.substring(phone.length() - 4);
    }
}
```

---

## 3. EmailService

```java
package com.payflow.notification.service;

import com.payflow.notification.model.EmailNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

/**
 * Sends emails via AWS SES (Simple Email Service).
 *
 * WHY AWS SES:
 * - High deliverability (built-in DKIM, SPF)
 * - Cost effective ($0.10 per 1000 emails)
 * - Handles bounces and complaints automatically
 * - Scales to millions of emails/day
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {

    private final SesClient sesClient;
    private final TemplateService templateService;

    @Value("${notification.email.from:noreply@payflow.com}")
    private String fromAddress;

    @Value("${notification.email.from-name:PayFlow}")
    private String fromName;

    public void send(EmailNotification notification) {
        // Step 1: Render HTML template with dynamic data
        String htmlBody = templateService.render(
                notification.getTemplateName(),
                notification.getTemplateData()
        );

        // Step 2: Build SES request
        // WHY SendEmailRequest: AWS SDK v2 builder pattern — type-safe, fluent API
        SendEmailRequest request = SendEmailRequest.builder()
                .source(fromName + " <" + fromAddress + ">")
                .destination(Destination.builder()
                        .toAddresses(notification.getRecipientEmail())
                        .build())
                .message(Message.builder()
                        .subject(Content.builder()
                                .data(notification.getSubject())
                                .charset("UTF-8")
                                .build())
                        .body(Body.builder()
                                .html(Content.builder()
                                        .data(htmlBody)
                                        .charset("UTF-8")
                                        .build())
                                // WHY text body: Fallback for email clients that don't support HTML
                                .text(Content.builder()
                                        .data(notification.getPlainTextFallback())
                                        .charset("UTF-8")
                                        .build())
                                .build())
                        .build())
                .build();

        // Step 3: Send via AWS SES
        try {
            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("✅ Email sent: to={}, messageId={}, template={}",
                    notification.getRecipientEmail(),
                    response.messageId(),
                    notification.getTemplateName());

        } catch (SesException e) {
            // WHY: Handle specific SES errors
            if (e.statusCode() == 400 && e.getMessage().contains("Throttling")) {
                log.warn("⏳ SES throttled. Will retry. to={}", notification.getRecipientEmail());
                throw e;  // Let consumer retry
            }
            log.error("❌ SES error sending email to {}: {}",
                    notification.getRecipientEmail(), e.getMessage());
            throw e;
        }
    }
}
```

### AWS SES Configuration

```java
package com.payflow.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

import java.net.URI;

@Configuration
public class AwsSesConfig {

    @Value("${aws.region:ap-south-1}")
    private String region;

    // WHY: LocalStack endpoint for local development
    @Value("${aws.ses.endpoint:#{null}}")
    private String endpoint;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Bean
    public SesClient sesClient() {
        var builder = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));

        // WHY: Override endpoint for LocalStack (local development)
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }
}
```

---

## 4. SmsService

```java
package com.payflow.notification.service;

import com.payflow.notification.model.SmsNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends SMS messages via AWS SNS (Simple Notification Service).
 *
 * WHY AWS SNS for SMS:
 * - Global delivery (supports 200+ countries)
 * - Handles carrier routing automatically
 * - Supports Transactional SMS (high priority, OTP) and Promotional SMS
 * - Cost: ~$0.02 per SMS in India
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmsService {

    private final SnsClient snsClient;

    public void send(SmsNotification notification) {
        // WHY message attributes: Tell SNS this is Transactional (not marketing)
        // Transactional = higher delivery priority + works on DND numbers
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("AWS.SNS.SMS.SMSType", MessageAttributeValue.builder()
                .stringValue("Transactional")  // vs "Promotional"
                .dataType("String")
                .build());

        // WHY SenderID: Shows "PayFlow" as sender instead of random number
        attributes.put("AWS.SNS.SMS.SenderID", MessageAttributeValue.builder()
                .stringValue("PayFlow")
                .dataType("String")
                .build());

        PublishRequest request = PublishRequest.builder()
                .phoneNumber(notification.getPhoneNumber())  // E.164 format: +919876543210
                .message(notification.getMessage())
                .messageAttributes(attributes)
                .build();

        try {
            PublishResponse response = snsClient.publish(request);
            log.info("✅ SMS sent: to=***{}, messageId={}",
                    notification.getPhoneNumber().substring(
                            notification.getPhoneNumber().length() - 4),
                    response.messageId());

        } catch (Exception e) {
            log.error("❌ Failed to send SMS to {}: {}",
                    maskPhone(notification.getPhoneNumber()), e.getMessage());
            throw e;
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "***" + phone.substring(phone.length() - 4);
    }
}
```

### AWS SNS Configuration

```java
package com.payflow.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

import java.net.URI;

@Configuration
public class AwsSnsConfig {

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Value("${aws.sns.endpoint:#{null}}")
    private String endpoint;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Bean
    public SnsClient snsClient() {
        var builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }
}
```

---

## 5. TemplateService

```java
package com.payflow.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Renders HTML email templates using Thymeleaf.
 *
 * WHY Thymeleaf:
 * - Spring Boot default template engine (well-supported)
 * - Natural templates (valid HTML even without processing)
 * - Powerful expressions (iteration, conditionals, formatting)
 * - Hot-reload in development (change template, see result immediately)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateEngine templateEngine;

    /**
     * Render an HTML template with dynamic data.
     *
     * @param templateName Name of template file (without .html extension)
     * @param data Key-value pairs to inject into template
     * @return Rendered HTML string
     */
    public String render(String templateName, Map<String, Object> data) {
        // WHY Context: Thymeleaf's way of passing variables to templates
        Context context = new Context();
        context.setVariables(data);

        try {
            // WHY: processTemplateFile looks in src/main/resources/templates/{name}.html
            String html = templateEngine.process(templateName, context);
            log.debug("Rendered template: {} ({} chars)", templateName, html.length());
            return html;

        } catch (Exception e) {
            log.error("❌ Template rendering failed: template={}", templateName, e);
            // WHY fallback: If template breaks, send plain text instead of nothing
            return buildFallbackHtml(data);
        }
    }

    private String buildFallbackHtml(Map<String, Object> data) {
        // WHY: A payment notification MUST go out, even if template is broken
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");
        sb.append("<h2>PayFlow Notification</h2>");
        sb.append("<p>").append(data.getOrDefault("message", "Transaction update")).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }
}
```

---

## 6. Template Examples

### payment-success.html

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Payment Successful</title>
    <style>
        /* WHY inline styles: Email clients strip <style> blocks.
           For production, use an email CSS inliner tool. */
        body { font-family: 'Segoe UI', Arial, sans-serif; background: #f5f5f5; margin: 0; padding: 20px; }
        .container { max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; overflow: hidden; }
        .header { background: #4F46E5; color: white; padding: 30px; text-align: center; }
        .content { padding: 30px; }
        .amount { font-size: 36px; font-weight: bold; color: #4F46E5; text-align: center; margin: 20px 0; }
        .details { background: #f8f9fa; border-radius: 6px; padding: 20px; margin: 20px 0; }
        .row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #eee; }
        .footer { background: #f8f9fa; padding: 20px; text-align: center; font-size: 12px; color: #666; }
        .badge { display: inline-block; background: #10B981; color: white; padding: 4px 12px; border-radius: 12px; font-size: 14px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Payment Successful ✓</h1>
        </div>
        <div class="content">
            <p th:text="'Hi ' + ${customerName} + ','">Hi Customer,</p>
            <p>Your payment has been processed successfully.</p>

            <div class="amount">
                <span th:text="'₹' + ${amount}">₹5,000</span>
            </div>

            <div class="details">
                <div class="row">
                    <span>Transaction ID</span>
                    <span th:text="${transactionId}">pay_xK9mN2pQ3rS4</span>
                </div>
                <div class="row">
                    <span>Date</span>
                    <span th:text="${date}">Jan 15, 2024</span>
                </div>
                <div class="row">
                    <span>Merchant</span>
                    <span th:text="${merchantName}">ShopEasy</span>
                </div>
                <div class="row">
                    <span>Card</span>
                    <span th:text="'**** ' + ${cardLast4}">**** 4242</span>
                </div>
                <div class="row">
                    <span>Status</span>
                    <span class="badge">Successful</span>
                </div>
            </div>

            <p style="color: #666; font-size: 14px;">
                If you did not make this payment, please contact us immediately.
            </p>
        </div>
        <div class="footer">
            <p>PayFlow Payment Gateway</p>
            <p>This is an automated notification. Please do not reply.</p>
        </div>
    </div>
</body>
</html>
```

### refund-processed.html

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Refund Processed</title>
    <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; background: #f5f5f5; margin: 0; padding: 20px; }
        .container { max-width: 600px; margin: 0 auto; background: white; border-radius: 8px; overflow: hidden; }
        .header { background: #F59E0B; color: white; padding: 30px; text-align: center; }
        .content { padding: 30px; }
        .amount { font-size: 36px; font-weight: bold; color: #F59E0B; text-align: center; margin: 20px 0; }
        .details { background: #f8f9fa; border-radius: 6px; padding: 20px; margin: 20px 0; }
        .row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #eee; }
        .footer { background: #f8f9fa; padding: 20px; text-align: center; font-size: 12px; color: #666; }
        .badge { display: inline-block; background: #F59E0B; color: white; padding: 4px 12px; border-radius: 12px; font-size: 14px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Refund Processed ↩</h1>
        </div>
        <div class="content">
            <p th:text="'Hi ' + ${customerName} + ','">Hi Customer,</p>
            <p>Your refund has been processed. The amount will be credited to your account within 5-7 business days.</p>

            <div class="amount">
                <span th:text="'₹' + ${refundAmount}">₹2,000</span>
            </div>

            <div class="details">
                <div class="row">
                    <span>Refund ID</span>
                    <span th:text="${refundId}">ref_abc123</span>
                </div>
                <div class="row">
                    <span>Original Payment</span>
                    <span th:text="${originalPaymentId}">pay_xyz789</span>
                </div>
                <div class="row">
                    <span>Refund Date</span>
                    <span th:text="${date}">Jan 16, 2024</span>
                </div>
                <div class="row">
                    <span>Refund To</span>
                    <span th:text="'Card ending ' + ${cardLast4}">Card ending 4242</span>
                </div>
                <div class="row">
                    <span>Status</span>
                    <span class="badge">Refund Initiated</span>
                </div>
            </div>

            <p style="color: #666; font-size: 14px;">
                Please allow 5-7 business days for the refund to reflect in your account.
            </p>
        </div>
        <div class="footer">
            <p>PayFlow Payment Gateway</p>
            <p>This is an automated notification. Please do not reply.</p>
        </div>
    </div>
</body>
</html>
```

---

## 7. Testing with LocalStack

LocalStack simulates AWS services locally — no AWS account needed for development.

### docker-compose entry for LocalStack

```yaml
localstack:
  image: localstack/localstack:latest
  ports:
    - "4566:4566"  # All AWS services on one port
  environment:
    - SERVICES=ses,sns,dynamodb
    - DEFAULT_REGION=ap-south-1
    - DEBUG=1
  volumes:
    - "./init-localstack.sh:/etc/localstack/init/ready.d/init.sh"
```

### init-localstack.sh

```bash
#!/bin/bash
# WHY: Initialize LocalStack with required resources on startup

echo "🚀 Initializing LocalStack for notification-service..."

# Verify email identity (SES requires verified sender)
awslocal ses verify-email-identity \
    --email-address noreply@payflow.com

# Create SNS topic for SMS (optional, for topic-based SMS)
awslocal sns create-topic \
    --name payment-sms-alerts

echo "✅ LocalStack initialization complete!"
```

### Application properties for local development

```yaml
# application-local.yml
aws:
  region: ap-south-1
  access-key: test
  secret-key: test
  ses:
    endpoint: http://localhost:4566
  sns:
    endpoint: http://localhost:4566
```

### Integration Test with LocalStack

```java
package com.payflow.notification;

import com.payflow.notification.model.EmailNotification;
import com.payflow.notification.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@Testcontainers
class EmailServiceIntegrationTest {

    @Container
    static LocalStackContainer localStack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.SES);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.ses.endpoint", () -> localStack.getEndpointOverride(
                LocalStackContainer.Service.SES).toString());
        registry.add("aws.access-key", () -> localStack.getAccessKey());
        registry.add("aws.secret-key", () -> localStack.getSecretKey());
        registry.add("aws.region", () -> localStack.getRegion());
    }

    @Autowired
    private EmailService emailService;

    @Test
    void shouldSendPaymentSuccessEmail() {
        // Given
        EmailNotification notification = EmailNotification.builder()
                .recipientEmail("customer@example.com")
                .subject("Payment Successful - ₹5,000")
                .templateName("payment-success")
                .templateData(Map.of(
                        "customerName", "Raj Patel",
                        "amount", "5,000",
                        "transactionId", "pay_test123",
                        "date", "Jan 15, 2024",
                        "merchantName", "TestShop",
                        "cardLast4", "4242"
                ))
                .plainTextFallback("Your payment of ₹5,000 was successful.")
                .build();

        // When/Then: Should not throw
        assertThatNoException().isThrownBy(() -> emailService.send(notification));
    }
}
```

---

## 8. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Notification architecture | Kafka topics separate email from SMS for independent scaling |
| 2 | KafkaListener concurrency | `concurrency = "3"` runs 3 consumer threads for throughput |
| 3 | AWS SES | Transactional email service ($0.10 per 1000 emails) |
| 4 | AWS SNS | SMS delivery to 200+ countries with Transactional priority |
| 5 | Thymeleaf templates | HTML email rendering with dynamic data injection |
| 6 | Template fallback | Always send something, even if template rendering fails |
| 7 | LocalStack | Fake AWS services locally (no AWS account needed) |
| 8 | PII masking | Never log full phone numbers or email addresses |
| 9 | Email best practices | Inline CSS, UTF-8 charset, plain text fallback |
| 10 | Testcontainers | Spin up LocalStack in tests automatically |

---

## 9. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `MessageRejected: Email not verified` | SES requires verified sender in sandbox | Verify email: `aws ses verify-email-identity` |
| `ThrottlingException` from SES | Sending too fast (sandbox = 1 email/sec) | Request production access or add rate limiting |
| SMS not delivered | SNS sandbox only sends to verified numbers | Add phone to sandbox or request production |
| Template `TemplateInputException` | Template file not found | Check file exists at `resources/templates/{name}.html` |
| Thymeleaf `null` in template | Missing variable in templateData map | Add null checks: `th:text="${amount} ?: 'N/A'"` |
| LocalStack connection refused | Container not started | Run `docker-compose up localstack` first |
| `InvalidParameterValueException` SMS | Phone not in E.164 format | Use format: `+919876543210` (with country code) |
| Email lands in spam | No DKIM/SPF configured | Set up domain verification in SES |

---

## 10. Git Commit

```bash
# Stage notification service files
git add backend/notification-service/

# Commit
git commit -m "feat(notification): add email/SMS notifications with templates

- KafkaNotificationConsumer: listens on notification.email and notification.sms
- EmailService: AWS SES integration with HTML template rendering
- SmsService: AWS SNS integration with Transactional SMS type
- TemplateService: Thymeleaf HTML templates with fallback
- Templates: payment-success.html, refund-processed.html
- AWS config: SES and SNS clients with LocalStack support
- Integration test: Testcontainers + LocalStack for email verification
- init-localstack.sh: Auto-create SES identities and SNS topics"

# Push
git push origin feature/phase4-notification-service
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| 09c | Fraud Detection & Smart Routing | ✅ |
| 10 | Bank Simulator | ✅ |
| 11 | Settlement Service | ✅ |
| 12 | Webhook Service | ✅ |
| **13** | **Notification Service** | **📍 Current** |
| 14 | Docker & Containerization | 🔜 Next |
| 15a | Frontend Setup | ⬜ |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 14**, we'll containerize the entire system with **Docker**:
- Multi-stage Dockerfiles for each service
- docker-compose.yml with all services + infrastructure
- Network isolation (frontend, backend, data tiers)
- Health checks and startup dependencies

---

[← Previous: Part 12 — Webhook Service](./phase4-part12-webhook-service.md) | [Next: Part 14 — Docker & Containerization →](./phase4-part14-docker.md)
