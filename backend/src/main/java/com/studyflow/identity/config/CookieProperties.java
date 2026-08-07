package com.studyflow.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "studyflow.cookie")
public class CookieProperties {

    private boolean secure;
    private String sameSite;
    private int refreshTtlDays;

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }

    public int getRefreshTtlDays() {
        return refreshTtlDays;
    }

    public void setRefreshTtlDays(int refreshTtlDays) {
        this.refreshTtlDays = refreshTtlDays;
    }
}
