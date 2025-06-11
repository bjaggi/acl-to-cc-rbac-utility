package com.confluent.utility;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ACL Binding representation for JSON serialization
 */
public class ACLBinding {
    
    @JsonProperty("principal")
    private String principal;
    
    @JsonProperty("host")
    private String host;
    
    @JsonProperty("operation")
    private String operation;
    
    @JsonProperty("permission_type")
    private String permissionType;
    
    @JsonProperty("resource_type")
    private String resourceType;
    
    @JsonProperty("resource_name")
    private String resourceName;
    
    @JsonProperty("pattern_type")
    private String patternType;

    // Default constructor
    public ACLBinding() {}

    // Constructor with all fields
    public ACLBinding(String principal, String host, String operation, String permissionType,
                     String resourceType, String resourceName, String patternType) {
        this.principal = principal;
        this.host = host;
        this.operation = operation;
        this.permissionType = permissionType;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
        this.patternType = patternType;
    }

    // Getters and Setters
    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(String permissionType) {
        this.permissionType = permissionType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getPatternType() {
        return patternType;
    }

    public void setPatternType(String patternType) {
        this.patternType = patternType;
    }

    @Override
    public String toString() {
        return "ACLBinding{" +
                "principal='" + principal + '\'' +
                ", host='" + host + '\'' +
                ", operation='" + operation + '\'' +
                ", permissionType='" + permissionType + '\'' +
                ", resourceType='" + resourceType + '\'' +
                ", resourceName='" + resourceName + '\'' +
                ", patternType='" + patternType + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ACLBinding)) return false;

        ACLBinding that = (ACLBinding) o;

        if (principal != null ? !principal.equals(that.principal) : that.principal != null) return false;
        if (host != null ? !host.equals(that.host) : that.host != null) return false;
        if (operation != null ? !operation.equals(that.operation) : that.operation != null) return false;
        if (permissionType != null ? !permissionType.equals(that.permissionType) : that.permissionType != null)
            return false;
        if (resourceType != null ? !resourceType.equals(that.resourceType) : that.resourceType != null) return false;
        if (resourceName != null ? !resourceName.equals(that.resourceName) : that.resourceName != null) return false;
        return patternType != null ? patternType.equals(that.patternType) : that.patternType == null;
    }

    @Override
    public int hashCode() {
        int result = principal != null ? principal.hashCode() : 0;
        result = 31 * result + (host != null ? host.hashCode() : 0);
        result = 31 * result + (operation != null ? operation.hashCode() : 0);
        result = 31 * result + (permissionType != null ? permissionType.hashCode() : 0);
        result = 31 * result + (resourceType != null ? resourceType.hashCode() : 0);
        result = 31 * result + (resourceName != null ? resourceName.hashCode() : 0);
        result = 31 * result + (patternType != null ? patternType.hashCode() : 0);
        return result;
    }
} 