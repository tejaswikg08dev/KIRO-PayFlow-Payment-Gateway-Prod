package com.payflow.notification.service;

import com.payflow.common.event.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

/**
 * Service that sends emails via AWS SES.
 * Renders HTML templates and sends to the recipient.
 */
@Slf4j
@Service
public class EmailService {

    private final SesClient sesClient;
    private final TemplateService templateService;

    @Value("${notification.email.from:noreply@payflow.com}")
    private String fromAddress;

    public EmailService(SesClient sesClient, TemplateService templateService) {
        this.sesClient = sesClient;
        this.templateService = templateService;
    }

    /**
     * Send an email notification using the specified template.
     */
    public void sendEmail(NotificationEvent event) {
        String htmlBody = templateService.renderTemplate(
                event.getTemplateName(), event.getTemplateData());

        String subject = resolveSubject(event.getTemplateName());

        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .destination(Destination.builder()
                            .toAddresses(event.getRecipient())
                            .build())
                    .message(Message.builder()
                            .subject(Content.builder()
                                    .data(subject)
                                    .charset("UTF-8")
                                    .build())
                            .body(Body.builder()
                                    .html(Content.builder()
                                            .data(htmlBody)
                                            .charset("UTF-8")
                                            .build())
                                    .build())
                            .build())
                    .source(fromAddress)
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("Email sent via SES: messageId={}, recipient={}",
                    response.messageId(), event.getRecipient());

        } catch (SesException e) {
            log.error("AWS SES error: statusCode={}, message={}",
                    e.statusCode(), e.getMessage());
            throw new RuntimeException("Failed to send email via SES", e);
        }
    }

    private String resolveSubject(String templateName) {
        return switch (templateName) {
            case "payment-success" -> "Payment Successful - PayFlow";
            case "payment-failed" -> "Payment Failed - PayFlow";
            case "refund-processed" -> "Refund Processed - PayFlow";
            case "settlement-completed" -> "Settlement Completed - PayFlow";
            default -> "Notification from PayFlow";
        };
    }
}
