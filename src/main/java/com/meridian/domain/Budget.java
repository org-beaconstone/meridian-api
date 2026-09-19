package com.meridian.domain;

public class Budget {
    private String category;
    private long limit;

    public Budget() {}

    public Budget(String category, long limit) {
        this.category = category;
        this.limit = limit;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getLimit() { return limit; }
    public void setLimit(long limit) { this.limit = limit; }
}
