package com.meridian.domain;

import java.util.List;

public class Provider {
    private String id;
    private String name;
    private String description;
    private List<String> methods;

    public Provider() {}

    public Provider(String id, String name, String description, List<String> methods) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.methods = methods;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }
}
