package com.instacommerce.platform.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared configuration properties for internal service-to-service authentication.
 */
@ConfigurationProperties(prefix = "internal.service")
public class InternalServiceProperties {
    private String name = "";
    private String token = "";
    private Map<String, String> allowedCallers = new LinkedHashMap<>();
    private boolean strictPerServiceTokens;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token != null ? token : "";
    }

    public Map<String, String> getAllowedCallers() {
        return allowedCallers;
    }

    public void setAllowedCallers(Map<String, String> allowedCallers) {
        this.allowedCallers = allowedCallers != null ? allowedCallers : new LinkedHashMap<>();
    }

    public boolean isStrictPerServiceTokens() {
        return strictPerServiceTokens;
    }

    public void setStrictPerServiceTokens(boolean strictPerServiceTokens) {
        this.strictPerServiceTokens = strictPerServiceTokens;
    }
}
