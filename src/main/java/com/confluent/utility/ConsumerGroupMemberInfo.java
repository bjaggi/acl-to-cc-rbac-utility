package com.confluent.utility;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data class to hold consumer group member information
 */
public class ConsumerGroupMemberInfo {
    
    @JsonProperty("member_id")
    private String memberId;
    
    @JsonProperty("client_id")
    private String clientId;
    
    @JsonProperty("host")
    private String host;
    
    @JsonProperty("assigned_partitions")
    private int assignedPartitions;
    
    // Default constructor
    public ConsumerGroupMemberInfo() {}
    
    // Getters and Setters
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    
    public int getAssignedPartitions() { return assignedPartitions; }
    public void setAssignedPartitions(int assignedPartitions) { this.assignedPartitions = assignedPartitions; }
} 