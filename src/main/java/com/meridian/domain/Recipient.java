package com.meridian.domain;

public class Recipient {
    private String id;
    private String name;
    private String initials;
    private String detail;
    private String category;
    private String color;

    public Recipient() {}

    public Recipient(String id, String name, String initials, String detail, String category, String color) {
        this.id = id;
        this.name = name;
        this.initials = initials;
        this.detail = detail;
        this.category = category;
        this.color = color;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInitials() { return initials; }
    public void setInitials(String initials) { this.initials = initials; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
}
