package com.meridian.domain;

import java.util.List;

public class CatalogResponse {
    private String demoDate;
    private List<Recipient> recipients;
    private List<Provider> providers;

    public CatalogResponse(String demoDate, List<Recipient> recipients, List<Provider> providers) {
        this.demoDate = demoDate;
        this.recipients = recipients;
        this.providers = providers;
    }

    public String getDemoDate() { return demoDate; }
    public void setDemoDate(String demoDate) { this.demoDate = demoDate; }

    public List<Recipient> getRecipients() { return recipients; }
    public void setRecipients(List<Recipient> recipients) { this.recipients = recipients; }

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) { this.providers = providers; }
}
