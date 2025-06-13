package com.confluent.utility;

import com.fasterxml.jackson.annotation.JsonProperty;
import software.amazon.awssdk.services.glue.model.SchemaVersionNumber;

import java.time.Instant;
import java.util.Map;

/**
 * Data class to hold schema information from AWS Glue Schema Registry
 */
public class SchemaInfo {
    
    @JsonProperty("schema_id")
    private String schemaId;
    
    @JsonProperty("schema_name")
    private String schemaName;
    
    @JsonProperty("registry_name")
    private String registryName;
    
    @JsonProperty("schema_arn")
    private String schemaArn;
    
    @JsonProperty("version_number")
    private Long versionNumber;
    
    @JsonProperty("version_id")
    private String versionId;
    
    @JsonProperty("schema_definition")
    private String schemaDefinition;
    
    @JsonProperty("data_format")
    private String dataFormat;
    
    @JsonProperty("compatibility")
    private String compatibility;
    
    @JsonProperty("description")
    private String description;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("created_time")
    private String createdTime;
    
    @JsonProperty("updated_time")
    private String updatedTime;
    
    @JsonProperty("tags")
    private Map<String, String> tags;
    
    // Default constructor
    public SchemaInfo() {}
    
    // Constructor with basic information
    public SchemaInfo(String schemaId, String schemaName, String registryName) {
        this.schemaId = schemaId;
        this.schemaName = schemaName;
        this.registryName = registryName;
    }
    
    // Getters and Setters
    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
    
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    
    public String getRegistryName() { return registryName; }
    public void setRegistryName(String registryName) { this.registryName = registryName; }
    
    public String getSchemaArn() { return schemaArn; }
    public void setSchemaArn(String schemaArn) { this.schemaArn = schemaArn; }
    
    public Long getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Long versionNumber) { this.versionNumber = versionNumber; }
    
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    
    public String getSchemaDefinition() { return schemaDefinition; }
    public void setSchemaDefinition(String schemaDefinition) { this.schemaDefinition = schemaDefinition; }
    
    public String getDataFormat() { return dataFormat; }
    public void setDataFormat(String dataFormat) { this.dataFormat = dataFormat; }
    
    public String getCompatibility() { return compatibility; }
    public void setCompatibility(String compatibility) { this.compatibility = compatibility; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
    
    public String getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(String updatedTime) { this.updatedTime = updatedTime; }
    
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    
    @Override
    public String toString() {
        return "SchemaInfo{" +
                "schemaId='" + schemaId + '\'' +
                ", schemaName='" + schemaName + '\'' +
                ", registryName='" + registryName + '\'' +
                ", versionNumber=" + versionNumber +
                ", dataFormat='" + dataFormat + '\'' +
                '}';
    }
} 