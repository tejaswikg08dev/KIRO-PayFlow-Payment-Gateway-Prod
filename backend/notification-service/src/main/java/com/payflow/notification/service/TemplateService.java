package com.payflow.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Service to load and render HTML email templates using Thymeleaf.
 */
@Slf4j
@Service
public class TemplateService {

    private final TemplateEngine templateEngine;

    public TemplateService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Render an HTML template with the provided data.
     *
     * @param templateName the template name (without extension)
     * @param templateData key-value pairs to inject into the template
     * @return rendered HTML string
     */
    public String renderTemplate(String templateName, Map<String, String> templateData) {
        log.debug("Rendering template: {}", templateName);

        Context context = new Context();
        if (templateData != null) {
            templateData.forEach(context::setVariable);
        }

        try {
            return templateEngine.process(templateName, context);
        } catch (Exception e) {
            log.error("Failed to render template: {}, error={}", templateName, e.getMessage());
            // Fallback: return a simple text-based email
            return buildFallbackHtml(templateName, templateData);
        }
    }

    private String buildFallbackHtml(String templateName, Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");
        sb.append("<h2>PayFlow Notification</h2>");
        sb.append("<p>Template: ").append(templateName).append("</p>");
        if (data != null) {
            sb.append("<table>");
            data.forEach((key, value) -> sb.append("<tr><td><strong>")
                    .append(key).append(":</strong></td><td>")
                    .append(value).append("</td></tr>"));
            sb.append("</table>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}
