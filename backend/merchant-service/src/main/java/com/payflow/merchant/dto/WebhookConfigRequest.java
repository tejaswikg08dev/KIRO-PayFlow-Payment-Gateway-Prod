package com.payflow.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookConfigRequest {

    @NotBlank(message = "Webhook URL is required")
    @URL(message = "Invalid URL format")
    private String url;

    @NotEmpty(message = "At least one event must be specified")
    private String[] events;
}
