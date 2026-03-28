package com.Idempotency.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private boolean enabled = true;
    private String keyPrefix = "idp:";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return "idp:";
        }
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}