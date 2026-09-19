package com.meridian.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Transaction {
    private String id;
    private String reference;
    private String recipientId;
    private String name;
    private String category;
    private long amount;
    private String date;
    private String provider;
    private String method;
    private String status;
    private String note;

    public Transaction() {}

    public Transaction(String id, String reference, String recipientId, String name,
                       String category, long amount, String date, String provider,
                       String method, String status, String note) {
        this.id = id;
        this.reference = reference;
        this.recipientId = recipientId;
        this.name = name;
        this.category = category;
        this.amount = amount;
        this.date = date;
        this.provider = provider;
        this.method = method;
        this.status = status;
        this.note = note;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getRecipientId() { return recipientId; }
    public void setRecipientId(String recipientId) { this.recipientId = recipientId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
