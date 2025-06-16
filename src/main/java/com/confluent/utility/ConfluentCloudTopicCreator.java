package com.confluent.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.confluent.utility.StatusIcons.*;

/**
 * Confluent Cloud Topic Creator using Java API
 * Creates topics in Confluent Cloud based on MSK topic configurations
 */
public class ConfluentCloudTopicCreator {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfluentCloudTopicCreator.class);
    
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String apiSecret;
    private final boolean dryRun;
    private final boolean verbose;
    
    // Statistics
    private int topicsCreated = 0;
    private int topicsSkipped = 0;
    private int topicsFailed = 0;
    
    public ConfluentCloudTopicCreator(String baseUrl, String kafkaApiKey, String kafkaApiSecret, boolean dryRun, boolean verbose) {
        this.baseUrl = baseUrl;
        this.apiKey = kafkaApiKey;    // Use Kafka API key for topic operations
        this.apiSecret = kafkaApiSecret; // Use Kafka API secret for topic operations
        this.dryRun = dryRun;
        this.verbose = verbose;
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
                
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        
        logger.info("Confluent Cloud Topic Creator initialized with Kafka API keys (dry-run: {})", dryRun);
    }
    
    /**
     * Create topics from MSK topics JSON file
     */
    public void createTopicsFromFile(String topicsFilePath, String environment, String clusterId) throws ConfluentCloudException {
        logger.info("Reading MSK topics data from: {}", topicsFilePath);
        
        try {
            MSKTopicsData topicsData = objectMapper.readValue(new File(topicsFilePath), MSKTopicsData.class);
            
            logger.info("Loaded {} topics for creation", topicsData.topics.size());
            
            // Get existing topics to avoid duplicates
            Set<String> existingTopics = getExistingTopics(environment, clusterId);
            
            // Create topics
            createTopics(topicsData.topics, environment, clusterId, existingTopics);
            
            // Print summary
            printSummary();
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to read topics file: " + topicsFilePath, e);
        }
    }
    
    /**
     * Get existing topics from Confluent Cloud
     */
    private Set<String> getExistingTopics(String environment, String clusterId) throws ConfluentCloudException {
        logger.debug("Fetching existing topics for cluster: {}", clusterId);
        
        // Use Kafka REST Proxy API v3 for topic management
        String url = String.format("%s/kafka/v3/clusters/%s/topics", baseUrl, clusterId);
        logger.info("Fetching existing topics from: {}", url);
        logger.info("baseUrl: {}", baseUrl);
        

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                .addHeader("Content-Type", "application/json")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ConfluentCloudException("Failed to fetch existing topics: " + response.code());
            }
            
            String responseBody = response.body().string();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            
            Set<String> existingNames = new HashSet<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topics = (List<Map<String, Object>>) result.get("data");
            
            if (topics != null) {
                for (Map<String, Object> topic : topics) {
                    // For v3 API, the field is "topic_name"
                    String name = (String) topic.get("topic_name");
                    if (name != null) {
                        existingNames.add(name);
                    }
                }
            }
            
            logger.debug("Found {} existing topics", existingNames.size());
            return existingNames;
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to parse topics response", e);
        }
    }
    
    /**
     * Create topics in Confluent Cloud
     */
    private void createTopics(List<TopicInfo> topics, String environment, String clusterId, 
                             Set<String> existingTopics) throws ConfluentCloudException {
        
        if (topics.isEmpty()) {
            logger.info("No topics to create");
            return;
        }
        
        logger.info("Creating {} topics...", topics.size());
        
        for (TopicInfo topic : topics) {
            // Skip internal topics
            if (topic.is_internal || topic.internal) {
                logger.info("{} Skipping internal topic: {}", SKIPPED, topic.name);
                topicsSkipped++;
                continue;
            }
            
            // Skip if topic already exists
            if (existingTopics.contains(topic.name)) {
                logger.info("{} Topic '{}' already exists, skipping...", EXISTS, topic.name);
                topicsSkipped++;
                continue;
            }
            
            if (dryRun) {
                logger.info("{} Would create topic '{}' with {} partitions", 
                           DRY_RUN, topic.name, topic.partitions);
                continue;
            }
            
            try {
                createTopic(topic, environment, clusterId);
                topicsCreated++;
                logger.info("{} Created topic: {} ({} partitions)", SUCCESS, topic.name, topic.partitions);
            } catch (ConfluentCloudException e) {
                topicsFailed++;
                logger.error("{} Failed to create topic {}: {}", ERROR, topic.name, e.getMessage());
            }
        }
    }
    
    /**
     * Create a single topic
     */
    private void createTopic(TopicInfo topic, String environment, String clusterId) throws ConfluentCloudException {
        // Use Kafka REST Proxy API v3 for topic management
        String url = String.format("%s/kafka/v3/clusters/%s/topics", baseUrl, clusterId);
        
        // Build topic configuration for v3 API
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("topic_name", topic.name);
        requestBody.put("partitions_count", topic.partitions);
        
        // Add configurations (convert MSK configs to CC compatible ones)
        Map<String, String> configs = convertConfigurations(topic.configurations);
        if (!configs.isEmpty()) {
            // Convert configs to the expected format: array of {name, value} objects
            List<Map<String, String>> configsList = new ArrayList<>();
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                Map<String, String> configEntry = new HashMap<>();
                configEntry.put("name", entry.getKey());
                configEntry.put("value", entry.getValue());
                configsList.add(configEntry);
            }
            requestBody.put("configs", configsList);
        }
        
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            if (verbose) {
                logger.debug("Creating topic with request: {}", jsonBody);
            }
            
            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error details";
                    throw new ConfluentCloudException("Failed to create topic: " + response.code() + " - " + errorBody);
                }
                
                if (verbose) {
                    String responseBody = response.body().string();
                    logger.debug("Topic created: {}", responseBody);
                }
            }
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to create topic", e);
        }
    }
    
    /**
     * Convert MSK topic configurations to Confluent Cloud compatible format
     */
    private Map<String, String> convertConfigurations(Map<String, String> mskConfigs) {
        Map<String, String> ccConfigs = new HashMap<>();
        
        if (mskConfigs == null) {
            return ccConfigs;
        }
        
        // Map of MSK config -> CC config (only include supported ones)
        Map<String, String> configMapping = new HashMap<>();
        configMapping.put("cleanup.policy", "cleanup.policy");
        configMapping.put("retention.ms", "retention.ms");
        configMapping.put("retention.bytes", "retention.bytes");
        configMapping.put("segment.bytes", "segment.bytes");
        configMapping.put("min.insync.replicas", "min.insync.replicas");
        configMapping.put("unclean.leader.election.enable", "unclean.leader.election.enable");
        configMapping.put("max.message.bytes", "max.message.bytes");
        configMapping.put("compression.type", "compression.type");
        
        for (Map.Entry<String, String> entry : mskConfigs.entrySet()) {
            String mskKey = entry.getKey();
            String ccKey = configMapping.get(mskKey);
            
            if (ccKey != null) {
                ccConfigs.put(ccKey, entry.getValue());
                if (verbose) {
                    logger.debug("Mapped config: {} -> {}", mskKey, ccKey);
                }
            } else {
                if (verbose) {
                    logger.debug("Skipping unsupported config: {}", mskKey);
                }
            }
        }
        
        return ccConfigs;
    }
    

    /**
     * Print creation summary
     */
    private void printSummary() {
        logger.info("{} Topic Creation Summary:", SUMMARY);
        logger.info("======================");
        logger.info(formatSummaryLine("Topics Created", topicsCreated, "success"));
        logger.info(formatSummaryLine("Topics Skipped", topicsSkipped, "skipped"));
        logger.info(formatSummaryLine("Topics Failed", topicsFailed, "failed"));
        
        if (topicsFailed > 0) {
            logger.error("{} Some topics failed to create. Check logs for details.", ERROR);
        } else if (!dryRun) {
            logger.info("{} All topic creation operations completed successfully!", SUCCESS);
        }
    }
    
    /**
     * MSK Topics Data structure
     */
    public static class MSKTopicsData {
        public List<TopicInfo> topics;
        public MSKClusterMetadata cluster_metadata;
        public int topic_count;
        public String exported_at;
        
        public MSKTopicsData() {
            this.topics = new ArrayList<>();
        }
    }
    
    /**
     * Topic information structure
     */
    public static class TopicInfo {
        public boolean internal;
        public String name;
        public int partitions;
        public int replication_factor;
        public boolean is_internal;
        public List<PartitionInfo> partition_info;
        public Map<String, String> configurations;
        
        public TopicInfo() {
            this.partition_info = new ArrayList<>();
            this.configurations = new HashMap<>();
        }
    }
    
    /**
     * Partition information structure
     */
    public static class PartitionInfo {
        public int partition;
        public String leader;
        public List<String> replicas;
        public List<String> in_sync_replicas;
        
        public PartitionInfo() {
            this.replicas = new ArrayList<>();
            this.in_sync_replicas = new ArrayList<>();
        }
    }
    
    /**
     * MSK Cluster metadata structure
     */
    public static class MSKClusterMetadata {
        public String cluster_name;
        public int number_of_broker_nodes;
        public String cluster_arn;
        public String kafka_version;
        public String state;
        public String region;
        public String instance_type;
    }
    
    /**
     * Custom exception for Confluent Cloud operations
     */
    public static class ConfluentCloudException extends Exception {
        public ConfluentCloudException(String message) {
            super(message);
        }
        
        public ConfluentCloudException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Main method for command-line usage
     */
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            printHelp();
            System.exit(0);
        }
        
        // Parse arguments and flags
        String topicsFile = "generated_jsons/msk_topics.json";
        String configFile = "ccloud.config";
        boolean dryRun = false;
        boolean verbose = false;
        
        // Track if we've set custom files
        boolean topicsFileSet = false;
        boolean configFileSet = false;
        
        for (String arg : args) {
            if (arg.equals("--dry-run")) {
                dryRun = true;
            } else if (arg.equals("--verbose")) {
                verbose = true;
            } else if (!arg.startsWith("--")) {
                // First non-flag argument is topics file, second is config file
                if (!topicsFileSet) {
                    topicsFile = arg;
                    topicsFileSet = true;
                } else if (!configFileSet) {
                    configFile = arg;
                    configFileSet = true;
                }
            }
        }
        
        try {
            // Read configuration
            ConfluentCloudRBACApplicator.ConfluentCloudConfig config = 
                ConfluentCloudRBACApplicator.readConfig(configFile);
            
            // Create topic creator using Kafka API keys
            ConfluentCloudTopicCreator creator = new ConfluentCloudTopicCreator(
                config.restUrl, config.kafkaApiKey, config.kafkaApiSecret, dryRun, verbose);
            
            // Create topics
            creator.createTopicsFromFile(topicsFile, config.environment, config.cluster);
            
            if (dryRun) {
                System.out.println(DRY_RUN + " Dry run completed. Remove --dry-run to create topics.");
            } else {
                System.out.println(COMPLETED + " Topic creation completed successfully!");
                System.out.println(ARROW + " Next steps:");
                System.out.println("  " + BULLET + " Verify topics in Confluent Cloud console");
                System.out.println("  " + BULLET + " Test topic access with your applications");
                System.out.println("  " + BULLET + " Monitor topic performance and adjust configurations if needed");
            }
            
        } catch (Exception e) {
            logger.error("{} Topic creation failed: {}", ERROR, e.getMessage(), e);
            System.err.println(ERROR + " Topic creation failed: " + e.getMessage());
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("Confluent Cloud Topic Creator");
        System.out.println("Usage: java ConfluentCloudTopicCreator [topics_file] [config_file] [options]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  topics_file  MSK topics JSON file (default: generated_jsons/msk_topics.json)");
        System.out.println("  config_file  Confluent Cloud config file (default: ccloud.config)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run    Show what would be done without making changes");
        System.out.println("  --verbose    Enable verbose logging");
        System.out.println("  --help       Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java ConfluentCloudTopicCreator");
        System.out.println("  java ConfluentCloudTopicCreator --dry-run");
        System.out.println("  java ConfluentCloudTopicCreator my_topics.json my_config.properties --verbose");
    }
} 