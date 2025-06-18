package com.confluent.utility;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Data class to hold consumer group information
 */
public class ConsumerGroupInfo {
    
    @JsonProperty("group_id")
    private String groupId;
    
    @JsonProperty("state")
    private String state;
    
    @JsonProperty("coordinator")
    private String coordinator;
    
    @JsonProperty("member_count")
    private int memberCount;
    
    @JsonProperty("members")
    private List<ConsumerGroupMemberInfo> members;
    
    // Default constructor
    public ConsumerGroupInfo() {}
    
    // Getters and Setters
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    
    public String getCoordinator() { return coordinator; }
    public void setCoordinator(String coordinator) { this.coordinator = coordinator; }
    
    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
    
    public List<ConsumerGroupMemberInfo> getMembers() { return members; }
    public void setMembers(List<ConsumerGroupMemberInfo> members) { this.members = members; }
} 