package com.confluent.utility;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;

import java.util.List;
import java.util.Map;

/**
 * Data class to hold topic information including configurations
 */
public class TopicInfo {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("partitions")
    private int partitions;
    
    @JsonProperty("replication_factor")
    private int replicationFactor;
    
    @JsonProperty("is_internal")
    private boolean isInternal;
    
    @JsonProperty("partition_info")
    private List<PartitionInfo> partitionInfo;
    
    @JsonProperty("configurations")
    private Map<String, String> configurations;
    
    // Default constructor
    public TopicInfo() {}
    
    // Constructor from TopicDescription
    public TopicInfo(TopicDescription description) {
        this.name = description.name();
        this.partitions = description.partitions().size();
        this.isInternal = description.isInternal();
        
        // Calculate replication factor from first partition
        if (!description.partitions().isEmpty()) {
            this.replicationFactor = description.partitions().get(0).replicas().size();
        }
        
        // Convert partition info
        this.partitionInfo = description.partitions().stream()
                .map(PartitionInfo::new)
                .collect(java.util.stream.Collectors.toList());
    }
    
    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public int getPartitions() { return partitions; }
    public void setPartitions(int partitions) { this.partitions = partitions; }
    
    public int getReplicationFactor() { return replicationFactor; }
    public void setReplicationFactor(int replicationFactor) { this.replicationFactor = replicationFactor; }
    
    public boolean isInternal() { return isInternal; }
    public void setInternal(boolean internal) { isInternal = internal; }
    
    public List<PartitionInfo> getPartitionInfo() { return partitionInfo; }
    public void setPartitionInfo(List<PartitionInfo> partitionInfo) { this.partitionInfo = partitionInfo; }
    
    public Map<String, String> getConfigurations() { return configurations; }
    public void setConfigurations(Map<String, String> configurations) { this.configurations = configurations; }
    
    /**
     * Inner class to hold partition information
     */
    public static class PartitionInfo {
        @JsonProperty("partition")
        private int partition;
        
        @JsonProperty("leader")
        private String leader;
        
        @JsonProperty("replicas")
        private List<String> replicas;
        
        @JsonProperty("in_sync_replicas")
        private List<String> inSyncReplicas;
        
        public PartitionInfo() {}
        
        public PartitionInfo(TopicPartitionInfo tpi) {
            this.partition = tpi.partition();
            this.leader = tpi.leader() != null ? tpi.leader().toString() : "none";
            this.replicas = tpi.replicas().stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
            this.inSyncReplicas = tpi.isr().stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
        }
        
        // Getters and Setters
        public int getPartition() { return partition; }
        public void setPartition(int partition) { this.partition = partition; }
        
        public String getLeader() { return leader; }
        public void setLeader(String leader) { this.leader = leader; }
        
        public List<String> getReplicas() { return replicas; }
        public void setReplicas(List<String> replicas) { this.replicas = replicas; }
        
        public List<String> getInSyncReplicas() { return inSyncReplicas; }
        public void setInSyncReplicas(List<String> inSyncReplicas) { this.inSyncReplicas = inSyncReplicas; }
    }
} 