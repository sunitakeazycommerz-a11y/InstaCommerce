package com.instacommerce.notification.service;

import com.instacommerce.notification.domain.model.NotificationChannel;
import com.samskivert.mustache.Mustache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
public class TemplateService {
    private final ResourceLoader resourceLoader;
    private final Mustache.Compiler compiler;
    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    public TemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.compiler = Mustache.compiler().escapeHTML(true);
    }

    public String render(NotificationChannel channel, String templateId, Map<String, Object> variables) {
        String path = templatePath(channel, templateId);
        String template = templateCache.computeIfAbsent(path, this::loadTemplate);
        return compiler.compile(template).execute(variables);
    }

    private String loadTemplate(String path) {
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Template not found: " + path);
        }
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load template " + path, ex);
        }
    }

    private String templatePath(NotificationChannel channel, String templateId) {
        String folder = switch (channel) {
            case EMAIL -> "email";
            case SMS -> "sms";
            case PUSH -> "push";
        };
        return "classpath:templates/" + folder + "/" + templateId + ".mustache";
    }
}
