package com.payflow.notification.consumer;

import com.payflow.common.event.NotificationEvent;
import com.payflow.notification.service.EmailService;
import com.payflow.notification.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens to notification events and routes to email or SMS service.
 */
@Slf4j
@Component
public class KafkaNotificationConsumer {

    private final EmailService emailService;
    private final SmsService smsService;

    public KafkaNotificationConsumer(EmailService emailService, SmsService smsService) {
        this.emailService = emailService;
        this.smsService = smsService;
    }

    @KafkaListener(
            topics = "notification.email",
            groupId = "notification-consumers"
    )
    public void consumeEmailNotification(NotificationEvent event) {
        log.info("Received email notification: eventId={}, template={}, recipient={}",
                event.getEventId(), event.getTemplateName(), event.getRecipient());

        try {
            emailService.sendEmail(event);
            log.info("Email sent successfully: eventId={}", event.getEventId());
        } catch (Exception e) {
            log.error("Failed to send email: eventId={}, error={}",
                    event.getEventId(), e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "notification.sms",
            groupId = "notification-consumers"
    )
    public void consumeSmsNotification(NotificationEvent event) {
        log.info("Received SMS notification: eventId={}, recipient={}",
                event.getEventId(), event.getRecipient());

        try {
            smsService.sendSms(event);
            log.info("SMS sent successfully: eventId={}", event.getEventId());
        } catch (Exception e) {
            log.error("Failed to send SMS: eventId={}, error={}",
                    event.getEventId(), e.getMessage(), e);
        }
    }
}
