package com.confluent.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.cli.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeAclsResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.*;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * MSK ACL Extractor Utility
 * 
 * This utility connects to Amazon MSK clusters and extracts all Access Control Lists (ACLs)
 * to a JSON file for further processing or migration to Confluent Cloud RBAC.
 */
public class MSKACLExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(MSKACLExtractor.class);
    
    private final String clusterArn;
    private final String region;
    private final KafkaClient mskClient;
    private final GlueClient glueClient;
    private AdminClient adminClient;
    private ClusterInfo clusterInfo;

    public MSKACLExtractor(String clusterArn, String region) {
        this.clusterArn = clusterArn;
        this.region = region;
        this.mskClient = KafkaClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.glueClient = GlueClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Retrieve cluster information from AWS MSK
     */
    public ClusterInfo getClusterInfo() throws MSKACLExtractorException {
        try {
            DescribeClusterRequest request = DescribeClusterRequest.builder()
                    .clusterArn(clusterArn)
                    .build();
            
            DescribeClusterResponse response = mskClient.describeCluster(request);
            this.clusterInfo = response.clusterInfo();
            
            logger.info("Successfully retrieved cluster info for: {}", clusterInfo.clusterName());
            return clusterInfo;
            
        } catch (Exception e) {
            logger.error("Failed to describe cluster: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to describe cluster", e);
        }
    }

    /**
     * Get bootstrap servers from cluster info based on security protocol and SASL mechanism
     */
    public String getBootstrapServers(String securityProtocol, String saslMechanism) throws MSKACLExtractorException {
        try {
            GetBootstrapBrokersRequest request = GetBootstrapBrokersRequest.builder()
                    .clusterArn(clusterArn)
                    .build();
            
            GetBootstrapBrokersResponse response = mskClient.getBootstrapBrokers(request);
            
            String bootstrapServers = null;
            
            // Select bootstrap servers based on security protocol and SASL mechanism
            if ("SASL_SSL".equals(securityProtocol)) {
                if ("AWS_MSK_IAM".equals(saslMechanism)) {
                    bootstrapServers = response.bootstrapBrokerStringSaslIam();
                    logger.info("Using SASL_IAM bootstrap servers for AWS_MSK_IAM");
                } else if (saslMechanism != null && saslMechanism.startsWith("SCRAM")) {
                    bootstrapServers = response.bootstrapBrokerStringSaslScram();
                    logger.info("Using SASL_SCRAM bootstrap servers for SCRAM mechanism");
                }
            } else if ("SSL".equals(securityProtocol)) {
                bootstrapServers = response.bootstrapBrokerStringTls();
                logger.info("Using TLS bootstrap servers for SSL protocol");
            } else if ("PLAINTEXT".equals(securityProtocol)) {
                bootstrapServers = response.bootstrapBrokerString();
                logger.info("Using plaintext bootstrap servers");
            }
            
            // Fallback logic if specific type not available
            if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                logger.warn("Preferred bootstrap servers not available, falling back to alternatives");
                bootstrapServers = response.bootstrapBrokerStringSaslIam();
                if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                    bootstrapServers = response.bootstrapBrokerStringTls();
                }
                if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                    bootstrapServers = response.bootstrapBrokerStringSaslScram();
                }
                if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                    bootstrapServers = response.bootstrapBrokerString();
                }
            }
            
            if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                throw new MSKACLExtractorException("No bootstrap servers found in cluster response");
            }
            
            logger.info("Bootstrap servers: {}", bootstrapServers);
            return bootstrapServers;
            
        } catch (Exception e) {
            logger.error("Failed to get bootstrap brokers: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to get bootstrap brokers", e);
        }
    }

    /**
     * Connect to the MSK cluster
     */
    public void connectToCluster(String securityProtocol, String saslMechanism, 
                                String saslUsername, String saslPassword) throws MSKACLExtractorException {
        String bootstrapServers = getBootstrapServers(securityProtocol, saslMechanism);
        
        Properties config = new Properties();
        config.put("bootstrap.servers", bootstrapServers);
        config.put("security.protocol", securityProtocol);
        config.put("client.id", "msk-acl-extractor");
        
        // Configure SSL
        if ("SSL".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol)) {
            String trustStoreLocation = System.getProperty("javax.net.ssl.trustStore", "");
            String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword", "");
            
            // Only set truststore properties if they are non-empty and point to files (not directories)
            if (trustStoreLocation != null && !trustStoreLocation.isEmpty()) {
                java.io.File trustStoreFile = new java.io.File(trustStoreLocation);
                if (trustStoreFile.exists() && trustStoreFile.isFile()) {
                    config.put("ssl.truststore.location", trustStoreLocation);
                    if (trustStorePassword != null && !trustStorePassword.isEmpty()) {
                        config.put("ssl.truststore.password", trustStorePassword);
                    }
                } else {
                    logger.warn("Truststore location '{}' is not a valid file, skipping truststore configuration", trustStoreLocation);
                }
            }
            config.put("ssl.endpoint.identification.algorithm", "https");
        }
        
        // Configure SASL
        if (saslMechanism != null && !saslMechanism.isEmpty()) {
            config.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
            
            if ("AWS_MSK_IAM".equals(saslMechanism)) {
                config.put(SaslConfigs.SASL_JAAS_CONFIG, 
                    "software.amazon.msk.auth.iam.IAMLoginModule required;");
                config.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS, 
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
            } else if (saslUsername != null && saslPassword != null) {
                String jaasConfig = String.format(
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";",
                    saslUsername, saslPassword);
                config.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig);
            }
        }
        
        try {
            this.adminClient = AdminClient.create(config);
            logger.info("Successfully connected to MSK cluster");
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Failed to connect to Kafka cluster: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to connect to Kafka cluster", e);
        }
    }

    /**
     * List all ACLs from the MSK cluster
     */
    public List<ACLBinding> listACLs() throws MSKACLExtractorException {
        if (adminClient == null) {
            throw new MSKACLExtractorException("Not connected to cluster. Call connectToCluster() first.");
        }
        
        try {
            // Create an empty ACL filter to get all ACLs
            AclBindingFilter filter = AclBindingFilter.ANY;
            
            DescribeAclsResult result = adminClient.describeAcls(filter);
            Collection<AclBinding> aclBindings = result.values().get();
            
            List<ACLBinding> acls = new ArrayList<>();
            for (AclBinding binding : aclBindings) {
                ACLBinding aclBinding = new ACLBinding();
                aclBinding.setPrincipal(binding.entry().principal());
                aclBinding.setHost(binding.entry().host());
                aclBinding.setOperation(binding.entry().operation().name());
                aclBinding.setPermissionType(binding.entry().permissionType().name());
                aclBinding.setResourceType(binding.pattern().resourceType().name());
                aclBinding.setResourceName(binding.pattern().name());
                aclBinding.setPatternType(binding.pattern().patternType().name());
                
                acls.add(aclBinding);
            }
            
            logger.info("Retrieved {} ACLs from the cluster", acls.size());
            return acls;
            
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to list ACLs: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to list ACLs", e);
        }
    }

    /**
     * List all topics from the MSK cluster
     */
    public List<TopicInfo> listTopics() throws MSKACLExtractorException {
        if (adminClient == null) {
            throw new MSKACLExtractorException("Not connected to cluster. Call connectToCluster() first.");
        }
        
        try {
            // List topics
            ListTopicsResult listTopicsResult = adminClient.listTopics();
            Set<String> topicNames = listTopicsResult.names().get();
            
            if (topicNames.isEmpty()) {
                logger.info("No topics found in the cluster");
                return new ArrayList<>();
            }
            
            // Describe topics to get detailed information
            DescribeTopicsResult describeResult = adminClient.describeTopics(topicNames);
            Map<String, TopicDescription> topicDescriptions = describeResult.all().get();
            
            // Describe configurations for all topics
            List<ConfigResource> configResources = topicNames.stream()
                    .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
                    .collect(java.util.stream.Collectors.toList());
            
            DescribeConfigsResult configsResult = adminClient.describeConfigs(configResources);
            Map<ConfigResource, org.apache.kafka.clients.admin.Config> configs = configsResult.all().get();
            
            List<TopicInfo> topics = new ArrayList<>();
            
            for (Map.Entry<String, TopicDescription> entry : topicDescriptions.entrySet()) {
                String topicName = entry.getKey();
                TopicDescription description = entry.getValue();
                
                TopicInfo topicInfo = new TopicInfo(description);
                
                // Add configurations
                ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
                org.apache.kafka.clients.admin.Config config = configs.get(configResource);
                
                if (config != null) {
                    Map<String, String> configMap = new HashMap<>();
                    config.entries().forEach(entry1 -> {
                        // Only include non-default configurations that are set
                        if (!entry1.isDefault() && entry1.value() != null) {
                            configMap.put(entry1.name(), entry1.value());
                        }
                    });
                    topicInfo.setConfigurations(configMap);
                }
                
                topics.add(topicInfo);
            }
            
            logger.info("Retrieved {} topics from the cluster", topics.size());
            return topics;
            
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to list topics: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to list topics", e);
        }
    }

    /**
     * List all schemas from AWS Glue Schema Registry
     */
    public List<SchemaInfo> listSchemas() throws MSKACLExtractorException {
        List<SchemaInfo> allSchemas = new ArrayList<>();
        
        try {
            // First, list all registries
            ListRegistriesRequest listRegistriesRequest = ListRegistriesRequest.builder().build();
            ListRegistriesResponse registriesResponse = glueClient.listRegistries(listRegistriesRequest);
            
            for (RegistryListItem registry : registriesResponse.registries()) {
                logger.info("Processing registry: {}", registry.registryName());
                
                try {
                    // List schemas in this registry
                    ListSchemasRequest listSchemasRequest = ListSchemasRequest.builder()
                            .registryId(RegistryId.builder()
                                    .registryName(registry.registryName())
                                    .build())
                            .build();
                    
                    ListSchemasResponse schemasResponse = glueClient.listSchemas(listSchemasRequest);
                    
                    for (SchemaListItem schemaItem : schemasResponse.schemas()) {
                        try {
                            // Get detailed schema information
                            GetSchemaRequest getSchemaRequest = GetSchemaRequest.builder()
                                    .schemaId(SchemaId.builder()
                                            .schemaName(schemaItem.schemaName())
                                            .registryName(registry.registryName())
                                            .build())
                                    .build();
                            
                            GetSchemaResponse schemaResponse = glueClient.getSchema(getSchemaRequest);
                            
                            // List all versions of this schema
                            ListSchemaVersionsRequest listVersionsRequest = ListSchemaVersionsRequest.builder()
                                    .schemaId(SchemaId.builder()
                                            .schemaName(schemaItem.schemaName())
                                            .registryName(registry.registryName())
                                            .build())
                                    .build();
                            
                            ListSchemaVersionsResponse versionsResponse = glueClient.listSchemaVersions(listVersionsRequest);
                            
                            // Get details for each version
                            for (SchemaVersionListItem versionItem : versionsResponse.schemas()) {
                                try {
                                    GetSchemaVersionRequest versionRequest = GetSchemaVersionRequest.builder()
                                            .schemaId(SchemaId.builder()
                                                    .schemaName(schemaItem.schemaName())
                                                    .registryName(registry.registryName())
                                                    .build())
                                            .schemaVersionNumber(SchemaVersionNumber.builder()
                                                    .versionNumber(versionItem.versionNumber())
                                                    .build())
                                            .build();
                                    
                                    GetSchemaVersionResponse versionResponse = glueClient.getSchemaVersion(versionRequest);
                                    
                                    // Create SchemaInfo object for this version
                                    SchemaInfo schemaInfo = new SchemaInfo();
                                    schemaInfo.setSchemaId(schemaResponse.schemaArn());
                                    schemaInfo.setSchemaName(schemaResponse.schemaName());
                                    schemaInfo.setRegistryName(registry.registryName());
                                    schemaInfo.setSchemaArn(schemaResponse.schemaArn());
                                    schemaInfo.setDataFormat(schemaResponse.dataFormat() != null ? schemaResponse.dataFormat().toString() : null);
                                    schemaInfo.setCompatibility(schemaResponse.compatibility() != null ? schemaResponse.compatibility().toString() : null);
                                    schemaInfo.setDescription(schemaResponse.description());
                                    schemaInfo.setStatus(versionItem.status() != null ? versionItem.status().toString() : null);
                                    schemaInfo.setCreatedTime(versionItem.createdTime() != null ? versionItem.createdTime().toString() : null);
                                    schemaInfo.setUpdatedTime(schemaResponse.updatedTime() != null ? schemaResponse.updatedTime().toString() : null);
                                    
                                    // Set version information
                                    schemaInfo.setVersionNumber(versionResponse.versionNumber());
                                    schemaInfo.setVersionId(versionResponse.schemaVersionId());
                                    schemaInfo.setSchemaDefinition(versionResponse.schemaDefinition());
                                    
                                    allSchemas.add(schemaInfo);
                                    
                                } catch (Exception e) {
                                    logger.warn("Failed to get details for version {} of schema {} in registry {}: {}", 
                                               versionItem.versionNumber(), schemaItem.schemaName(), registry.registryName(), e.getMessage());
                                }
                            }
                            
                        } catch (Exception e) {
                            logger.warn("Failed to get details for schema {} in registry {}: {}", 
                                       schemaItem.schemaName(), registry.registryName(), e.getMessage());
                        }
                    }
                    
                } catch (Exception e) {
                    logger.warn("Failed to list schemas in registry {}: {}", registry.registryName(), e.getMessage());
                }
            }
            
            logger.info("Retrieved {} schemas from {} registries", allSchemas.size(), registriesResponse.registries().size());
            return allSchemas;
            
        } catch (SdkClientException e) {
            logger.warn("Could not connect to AWS Glue Schema Registry: {}", e.getMessage());
            logger.info("Continuing without schema extraction...");
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Failed to list schemas: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to list schemas", e);
        }
    }

    /**
     * Export ACLs and Topics to separate JSON files in generated_jsons folder
     */
    public void exportACLsAndTopicsToJSON(boolean includeMetadata) throws MSKACLExtractorException {
        // Create output directory if it doesn't exist
        java.io.File outputDir = new java.io.File("generated_jsons");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
            logger.info("Created output directory: generated_jsons");
        }
        
        // Export ACLs to generated_jsons/msk_acls.json
        exportACLsToJSON("generated_jsons/msk_acls.json", includeMetadata);
        
        // Export Topics to generated_jsons/msk_topics.json
        exportTopicsToJSON("generated_jsons/msk_topics.json", includeMetadata);
        
        // Export Schemas to generated_jsons/msk_schemas.json
        exportSchemasToJSON("generated_jsons/msk_schemas.json", includeMetadata);
    }
    
    /**
     * Export ACLs to a JSON file
     */
    public void exportACLsToJSON(String outputFile, boolean includeMetadata) throws MSKACLExtractorException {
        List<ACLBinding> acls = listACLs();
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("acls", acls);
        exportData.put("acl_count", acls.size());
        exportData.put("exported_at", LocalDateTime.now().toString());
        
        if (includeMetadata && clusterInfo != null) {
            Map<String, Object> metadata = createClusterMetadata();
            exportData.put("cluster_metadata", metadata);
        }
        
        writeJSONFile(outputFile, exportData);
        logger.info("Successfully exported {} ACLs to {}", acls.size(), outputFile);
    }
    
    /**
     * Export Topics to a JSON file
     */
    public void exportTopicsToJSON(String outputFile, boolean includeMetadata) throws MSKACLExtractorException {
        List<TopicInfo> topics = listTopics();
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("topics", topics);
        exportData.put("topic_count", topics.size());
        exportData.put("exported_at", LocalDateTime.now().toString());
        
        if (includeMetadata && clusterInfo != null) {
            Map<String, Object> metadata = createClusterMetadata();
            exportData.put("cluster_metadata", metadata);
        }
        
        writeJSONFile(outputFile, exportData);
        logger.info("Successfully exported {} topics to {}", topics.size(), outputFile);
    }
    
    /**
     * Export Schemas to a JSON file
     */
    public void exportSchemasToJSON(String outputFile, boolean includeMetadata) throws MSKACLExtractorException {
        List<SchemaInfo> schemas = listSchemas();
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("schemas", schemas);
        exportData.put("schema_count", schemas.size());
        exportData.put("exported_at", LocalDateTime.now().toString());
        
        if (includeMetadata && clusterInfo != null) {
            Map<String, Object> metadata = createClusterMetadata();
            exportData.put("cluster_metadata", metadata);
        }
        
        writeJSONFile(outputFile, exportData);
        logger.info("Successfully exported {} schemas to {}", schemas.size(), outputFile);
    }
    
    /**
     * Create cluster metadata object
     */
    private Map<String, Object> createClusterMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("cluster_name", clusterInfo.clusterName());
        metadata.put("cluster_arn", clusterArn);
        metadata.put("state", clusterInfo.state().toString());
        metadata.put("kafka_version", clusterInfo.currentVersion());
        metadata.put("number_of_broker_nodes", clusterInfo.numberOfBrokerNodes());
        if (clusterInfo.brokerNodeGroupInfo() != null) {
            metadata.put("instance_type", clusterInfo.brokerNodeGroupInfo().instanceType());
        }
        metadata.put("region", region);
        return metadata;
    }
    
    /**
     * Write data to JSON file
     */
    private void writeJSONFile(String outputFile, Map<String, Object> data) throws MSKACLExtractorException {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            
            try (FileWriter writer = new FileWriter(outputFile)) {
                mapper.writeValue(writer, data);
            }
            
        } catch (IOException e) {
            logger.error("Failed to write to file {}: {}", outputFile, e.getMessage());
            throw new MSKACLExtractorException("Failed to write to file", e);
        }
    }

    /**
     * Close resources
     */
    public void close() {
        if (adminClient != null) {
            adminClient.close();
        }
        if (mskClient != null) {
            mskClient.close();
        }
        if (glueClient != null) {
            glueClient.close();
        }
    }

    public static void main(String[] args) {
        Options options = createOptions();
        
        try {
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd = parser.parse(options, args);
            
            if (cmd.hasOption("help")) {
                printHelp(options);
                return;
            }
            
            String clusterArn = cmd.getOptionValue("cluster-arn");
            String region = cmd.getOptionValue("region", "us-east-1");
            // Note: output-file option is ignored - files are automatically saved to generated_jsons/ folder
            String securityProtocol = cmd.getOptionValue("security-protocol", "SSL");
            String saslMechanism = cmd.getOptionValue("sasl-mechanism");
            String saslUsername = cmd.getOptionValue("sasl-username");
            String saslPassword = cmd.getOptionValue("sasl-password");
            boolean noMetadata = cmd.hasOption("no-metadata");
            boolean verbose = cmd.hasOption("verbose");
            
            if (clusterArn == null) {
                System.err.println("Error: --cluster-arn is required");
                printHelp(options);
                System.exit(1);
            }
            
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }
            
            MSKACLExtractor extractor = new MSKACLExtractor(clusterArn, region);
            
            try {
                logger.info("Connecting to MSK cluster...");
                extractor.getClusterInfo();
                extractor.connectToCluster(securityProtocol, saslMechanism, saslUsername, saslPassword);
                
                logger.info("Exporting ACLs, Topics, and Schemas...");
                extractor.exportACLsAndTopicsToJSON(!noMetadata);
                
                System.out.println("✅ Successfully exported ACLs to generated_jsons/msk_acls.json");
                System.out.println("✅ Successfully exported Topics to generated_jsons/msk_topics.json");
                System.out.println("✅ Successfully exported Schemas to generated_jsons/msk_schemas.json");
                
            } finally {
                extractor.close();
            }
            
        } catch (ParseException e) {
            System.err.println("Error parsing command line: " + e.getMessage());
            printHelp(options);
            System.exit(1);
        } catch (MSKACLExtractorException e) {
            logger.error("Error: {}", e.getMessage());
            System.err.println("❌ Failed to export ACLs and Topics: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Options createOptions() {
        Options options = new Options();
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("cluster-arn")
                .hasArg()
                .required()
                .desc("ARN of the MSK cluster")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("region")
                .hasArg()
                .desc("AWS region (default: us-east-1)")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("output-file")
                .hasArg()
                .desc("Legacy option - outputs now go to generated_jsons/ folder automatically")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("security-protocol")
                .hasArg()
                .desc("Security protocol: SSL, SASL_SSL, PLAINTEXT, SASL_PLAINTEXT (default: SSL)")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("sasl-mechanism")
                .hasArg()
                .desc("SASL mechanism: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, AWS_MSK_IAM")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("sasl-username")
                .hasArg()
                .desc("SASL username if required")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("sasl-password")
                .hasArg()
                .desc("SASL password if required")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("no-metadata")
                .desc("Exclude cluster metadata from export")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder("v")
                .longOpt("verbose")
                .desc("Enable verbose logging")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder("h")
                .longOpt("help")
                .desc("Show this help message")
                .build());
        
        return options;
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar msk-acl-extractor.jar", 
                "Amazon MSK ACL and Topic Extractor\n\n" +
                "Connects to an Amazon MSK cluster and exports:\n" +
                "  • ACLs to generated_jsons/msk_acls.json\n" +
                "  • Topics to generated_jsons/msk_topics.json\n" +
                "  • Schemas to generated_jsons/msk_schemas.json\n\n" +
                "Example usage:\n" +
                "java -jar msk-acl-extractor.jar --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123\n\n" +
                "Options:", 
                options, 
                "\nFor more information, visit: https://github.com/confluent/acl-to-cc-rbac-utility");
    }

    /**
     * Custom exception for MSK ACL Extractor
     */
    public static class MSKACLExtractorException extends Exception {
        public MSKACLExtractorException(String message) {
            super(message);
        }
        
        public MSKACLExtractorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
} 