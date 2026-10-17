package com.instacommerce.mobilebff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "mobile-bff")
public class MobileBffProperties {

    private JwtProperties jwt = new JwtProperties();
    private UpstreamProperties upstream = new UpstreamProperties();

    public JwtProperties getJwt() {
        return jwt;
    }

    public void setJwt(JwtProperties jwt) {
        this.jwt = jwt;
    }

    public UpstreamProperties getUpstream() {
        return upstream;
    }

    public void setUpstream(UpstreamProperties upstream) {
        this.upstream = upstream;
    }

    public static class JwtProperties {
        private String issuer = "instacommerce-identity";
        private String publicKey;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }
    }

    public static class UpstreamProperties {
        private String catalogUrl = "http://catalog-service:8082";
        private String searchUrl = "http://search-service:8086";
        private String featureFlagUrl = "http://config-feature-flag-service:8096";
        private int timeoutMs = 2000;

        public String getCatalogUrl() {
            return catalogUrl;
        }

        public void setCatalogUrl(String catalogUrl) {
            this.catalogUrl = catalogUrl;
        }

        public String getSearchUrl() {
            return searchUrl;
        }

        public void setSearchUrl(String searchUrl) {
            this.searchUrl = searchUrl;
        }

        public String getFeatureFlagUrl() {
            return featureFlagUrl;
        }

        public void setFeatureFlagUrl(String featureFlagUrl) {
            this.featureFlagUrl = featureFlagUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
