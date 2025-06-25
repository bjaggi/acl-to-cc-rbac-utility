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
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

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
    private final String schemaRegistryUrl;
    private final String schemaRegistryAuthType;
    private final String schemaRegistryUsername;
    private final String schemaRegistryPassword;
    private final String schemaRegistryApiKey;
    private final String schemaRegistryApiSecret;
    private final String schemaRegistryToken;
    private final String schemaRegistrySslKeystore;
    private final String schemaRegistrySslKeystorePassword;
    private final String schemaRegistrySslTruststore;
    private final String schemaRegistrySslTruststorePassword;

    public MSKACLExtractor(String clusterArn, String region, String schemaRegistryUrl, 
                          String schemaRegistryAuthType, String schemaRegistryUsername, String schemaRegistryPassword,
                          String schemaRegistryApiKey, String schemaRegistryApiSecret, String schemaRegistryToken,
                          String schemaRegistrySslKeystore, String schemaRegistrySslKeystorePassword,
                          String schemaRegistrySslTruststore, String schemaRegistrySslTruststorePassword) {
        this.clusterArn = clusterArn;
        this.region = region;
        this.schemaRegistryUrl = schemaRegistryUrl;
        this.schemaRegistryAuthType = schemaRegistryAuthType;
        this.schemaRegistryUsername = schemaRegistryUsername;
        this.schemaRegistryPassword = schemaRegistryPassword;
        this.schemaRegistryApiKey = schemaRegistryApiKey;
        this.schemaRegistryApiSecret = schemaRegistryApiSecret;
        this.schemaRegistryToken = schemaRegistryToken;
        this.schemaRegistrySslKeystore = schemaRegistrySslKeystore;
        this.schemaRegistrySslKeystorePassword = schemaRegistrySslKeystorePassword;
        this.schemaRegistrySslTruststore = schemaRegistrySslTruststore;
        this.schemaRegistrySslTruststorePassword = schemaRegistrySslTruststorePassword;
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
    public List<SchemaInfo> listSchemas(String sourceOfSchemas) throws MSKACLExtractorException {
        switch (sourceOfSchemas.toLowerCase()) {
            case "glue":
                return listSchemasFromGlue();
            case "schemaregistry":
                return listSchemasFromSchemaRegistry();
            case "apicurio":
                return listSchemasFromApicurio();
            case "none":
                logger.info("Schema extraction disabled (source: none)");
                return new ArrayList<>();
            default:
                throw new MSKACLExtractorException("Invalid schema source: " + sourceOfSchemas);
        }
    }

    public List<SchemaInfo> listSchemasFromGlue() throws MSKACLExtractorException {
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
     * List all schemas from Confluent Schema Registry
     */
    public List<SchemaInfo> listSchemasFromSchemaRegistry() throws MSKACLExtractorException {
        List<SchemaInfo> allSchemas = new ArrayList<>();
        
        try {
            logger.info("Connecting to Schema Registry at: {}", schemaRegistryUrl);
            
            // Get all subjects
            List<String> subjects = getSchemaRegistrySubjects();
            
            for (String subject : subjects) {
                try {
                    // Get all versions for this subject
                    List<Integer> versions = getSchemaRegistryVersions(subject);
                    
                    for (Integer version : versions) {
                        try {
                            SchemaInfo schemaInfo = getSchemaRegistrySchema(subject, version);
                            if (schemaInfo != null) {
                                allSchemas.add(schemaInfo);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to get schema for subject {} version {}: {}", 
                                       subject, version, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get versions for subject {}: {}", subject, e.getMessage());
                }
            }
            
            logger.info("Retrieved {} schemas from Schema Registry", allSchemas.size());
            return allSchemas;
            
        } catch (Exception e) {
            logger.error("Failed to list schemas from Schema Registry: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to list schemas from Schema Registry", e);
        }
    }

    /**
     * List all schemas from Apicurio Registry
     */
    public List<SchemaInfo> listSchemasFromApicurio() throws MSKACLExtractorException {
        List<SchemaInfo> allSchemas = new ArrayList<>();
        
        try {
            logger.info("Connecting to Apicurio Registry at: {}", schemaRegistryUrl);
            
            // Get all artifacts from Apicurio
            List<Map<String, Object>> artifacts = getApicurioArtifacts();
            
            for (Map<String, Object> artifact : artifacts) {
                try {
                    String artifactId = (String) artifact.get("id");
                    String groupId = (String) artifact.get("groupId");
                    
                    // Get all versions for this artifact
                    List<Map<String, Object>> versions = getApicurioVersions(groupId, artifactId);
                    
                    for (Map<String, Object> versionMeta : versions) {
                        try {
                            SchemaInfo schemaInfo = getApicurioSchema(groupId, artifactId, versionMeta);
                            if (schemaInfo != null) {
                                allSchemas.add(schemaInfo);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to get schema for artifact {}/{} version {}: {}", 
                                       groupId, artifactId, versionMeta.get("version"), e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get versions for artifact {}: {}", artifact.get("id"), e.getMessage());
                }
            }
            
            logger.info("Retrieved {} schemas from Apicurio Registry", allSchemas.size());
            return allSchemas;
            
        } catch (Exception e) {
            logger.error("Failed to list schemas from Apicurio Registry: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to list schemas from Apicurio Registry", e);
        }
    }

    /**
     * List all consumer groups from the MSK cluster
     */
    public List<ConsumerGroupInfo> listConsumerGroups() throws MSKACLExtractorException {
        if (adminClient == null) {
            throw new MSKACLExtractorException("Not connected to cluster. Call connectToCluster() first.");
        }
        
        try {
            // List consumer groups
            ListConsumerGroupsResult listResult = adminClient.listConsumerGroups();
            Collection<ConsumerGroupListing> groupListings = listResult.all().get();
            
            if (groupListings.isEmpty()) {
                logger.info("No consumer groups found in the cluster");
                return new ArrayList<>();
            }
            
            // Get group IDs for detailed description
            Set<String> groupIds = groupListings.stream()
                    .map(ConsumerGroupListing::groupId)
                    .collect(java.util.stream.Collectors.toSet());
            
            // Describe consumer groups for detailed information
            DescribeConsumerGroupsResult describeResult = adminClient.describeConsumerGroups(groupIds);
            Map<String, ConsumerGroupDescription> groupDescriptions = describeResult.all().get();
            
            List<ConsumerGroupInfo> consumerGroups = new ArrayList<>();
            
            for (Map.Entry<String, ConsumerGroupDescription> entry : groupDescriptions.entrySet()) {
                String groupId = entry.getKey();
                ConsumerGroupDescription description = entry.getValue();
                
                ConsumerGroupInfo groupInfo = new ConsumerGroupInfo();
                groupInfo.setGroupId(groupId);
                groupInfo.setState(description.state().toString());
                groupInfo.setCoordinator(description.coordinator() != null ? 
                    description.coordinator().host() + ":" + description.coordinator().port() : "unknown");
                
                // Get member information
                List<ConsumerGroupMemberInfo> members = new ArrayList<>();
                description.members().forEach(member -> {
                    ConsumerGroupMemberInfo memberInfo = new ConsumerGroupMemberInfo();
                    memberInfo.setMemberId(member.consumerId());
                    memberInfo.setClientId(member.clientId());
                    memberInfo.setHost(member.host());
                    memberInfo.setAssignedPartitions(member.assignment().topicPartitions().size());
                    members.add(memberInfo);
                });
                groupInfo.setMembers(members);
                groupInfo.setMemberCount(members.size());
                
                consumerGroups.add(groupInfo);
            }
            
            logger.info("Retrieved {} consumer groups from the cluster", consumerGroups.size());
            return consumerGroups;
            
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to list consumer groups: {}", e.getMessage());
            throw new MSKACLExtractorException("Failed to list consumer groups", e);
        }
    }

    /**
     * Export all MSK data (ACLs, Topics, Schemas, and Consumer Groups) to JSON files
     */
    public void exportACLsAndTopicsToJSON(boolean includeMetadata, String sourceOfSchemas) throws MSKACLExtractorException {
        createOutputDirectories();
        
        // Export ACLs
        exportACLsToJSON("generated_jsons/msk_jsons/msk_acls.json", includeMetadata);
        
        // Export Topics
        exportTopicsToJSON("generated_jsons/msk_jsons/msk_topics.json", includeMetadata);
        
        // Export Consumer Groups
        exportConsumerGroupsToJSON("generated_jsons/msk_jsons/msk_consumer_groups.json", includeMetadata);
        
        // Export Schemas based on source
        exportSchemasToJSON("generated_jsons/msk_jsons/msk_schemas.json", includeMetadata, sourceOfSchemas);
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
    public void exportSchemasToJSON(String outputFile, boolean includeMetadata, String sourceOfSchemas) throws MSKACLExtractorException {
        List<SchemaInfo> schemas = listSchemas(sourceOfSchemas);
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("schemas", schemas);
        exportData.put("schema_count", schemas.size());
        exportData.put("schema_source", sourceOfSchemas);
        exportData.put("exported_at", LocalDateTime.now().toString());
        
        if (includeMetadata && clusterInfo != null) {
            Map<String, Object> metadata = createClusterMetadata();
            exportData.put("cluster_metadata", metadata);
        }
        
        writeJSONFile(outputFile, exportData);
        logger.info("Successfully exported {} schemas from {} to {}", schemas.size(), sourceOfSchemas, outputFile);
    }
    
    /**
     * Export Consumer Groups to a JSON file
     */
    public void exportConsumerGroupsToJSON(String outputFile, boolean includeMetadata) throws MSKACLExtractorException {
        List<ConsumerGroupInfo> consumerGroups = listConsumerGroups();
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("consumer_groups", consumerGroups);
        exportData.put("consumer_group_count", consumerGroups.size());
        exportData.put("exported_at", LocalDateTime.now().toString());
        
        if (includeMetadata && clusterInfo != null) {
            Map<String, Object> metadata = createClusterMetadata();
            exportData.put("cluster_metadata", metadata);
        }
        
        writeJSONFile(outputFile, exportData);
        logger.info("Successfully exported {} consumer groups to {}", consumerGroups.size(), outputFile);
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

    /**
     * HTTP helper methods for Schema Registry and Apicurio
     */
    private String makeHttpRequest(String url, String method) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(url);
            connection = (HttpURLConnection) requestUrl.openConnection();
            
            // Configure SSL/TLS for HTTPS connections
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
                configureSSL(httpsConnection);
            }
            
            connection.setRequestMethod(method);
            connection.setRequestProperty("Content-Type", "application/json");
            
            // Add authentication based on auth type
            if (schemaRegistryAuthType != null) {
                switch (schemaRegistryAuthType.toLowerCase()) {
                    case "basic":
                        if (schemaRegistryUsername != null && !schemaRegistryUsername.trim().isEmpty()) {
                            String auth = schemaRegistryUsername + ":" + (schemaRegistryPassword != null ? schemaRegistryPassword : "");
                            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
                        }
                        break;
                    case "apikey":
                        if (schemaRegistryApiKey != null && !schemaRegistryApiKey.trim().isEmpty()) {
                            String auth = schemaRegistryApiKey + ":" + (schemaRegistryApiSecret != null ? schemaRegistryApiSecret : "");
                            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
                        }
                        break;
                    case "bearer":
                        if (schemaRegistryToken != null && !schemaRegistryToken.trim().isEmpty()) {
                            connection.setRequestProperty("Authorization", "Bearer " + schemaRegistryToken);
                        }
                        break;
                    case "mtls":
                        // mTLS is configured in configureSSL method
                        logger.info("Using mTLS authentication with client certificates");
                        break;
                    case "none":
                    default:
                        // No authentication
                        break;
                }
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                }
            } else {
                throw new IOException("HTTP " + responseCode + " error for URL: " + url);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * Configure SSL/TLS for HTTPS connections including mTLS support
     */
    private void configureSSL(HttpsURLConnection connection) throws IOException {
        try {
            if ("mtls".equals(schemaRegistryAuthType) && 
                schemaRegistrySslKeystore != null && !schemaRegistrySslKeystore.trim().isEmpty()) {
                
                // Create SSL context with keystore and truststore for mTLS
                SSLContext sslContext = SSLContext.getInstance("TLS");
                
                // Load keystore (client certificate)
                KeyStore keyStore = KeyStore.getInstance("JKS");
                try (FileInputStream keystoreFile = new FileInputStream(schemaRegistrySslKeystore)) {
                    char[] keystorePassword = schemaRegistrySslKeystorePassword != null ? 
                                             schemaRegistrySslKeystorePassword.toCharArray() : new char[0];
                    keyStore.load(keystoreFile, keystorePassword);
                }
                
                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] keystorePassword = schemaRegistrySslKeystorePassword != null ? 
                                         schemaRegistrySslKeystorePassword.toCharArray() : new char[0];
                keyManagerFactory.init(keyStore, keystorePassword);
                
                // Load truststore if provided
                TrustManagerFactory trustManagerFactory = null;
                if (schemaRegistrySslTruststore != null && !schemaRegistrySslTruststore.trim().isEmpty()) {
                    KeyStore trustStore = KeyStore.getInstance("JKS");
                    try (FileInputStream truststoreFile = new FileInputStream(schemaRegistrySslTruststore)) {
                        char[] truststorePassword = schemaRegistrySslTruststorePassword != null ? 
                                                   schemaRegistrySslTruststorePassword.toCharArray() : new char[0];
                        trustStore.load(truststoreFile, truststorePassword);
                    }
                    
                    trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trustManagerFactory.init(trustStore);
                }
                
                // Initialize SSL context
                sslContext.init(
                    keyManagerFactory.getKeyManagers(),
                    trustManagerFactory != null ? trustManagerFactory.getTrustManagers() : null,
                    new SecureRandom()
                );
                
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                logger.info("mTLS SSL context configured with keystore: {}", schemaRegistrySslKeystore);
                
            } else {
                // Standard TLS/HTTPS - use default SSL context
                // This handles regular HTTPS connections without client certificates
                logger.debug("Using default SSL context for TLS connection");
            }
            
            // Optional: Disable hostname verification for development (not recommended for production)
            // connection.setHostnameVerifier((hostname, session) -> true);
            
        } catch (Exception e) {
            logger.error("Failed to configure SSL/TLS: {}", e.getMessage());
            throw new IOException("SSL configuration failed", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private List<String> getSchemaRegistrySubjects() throws IOException {
        String response = makeHttpRequest(schemaRegistryUrl + "/subjects", "GET");
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response, List.class);
    }
    
    @SuppressWarnings("unchecked")
    private List<Integer> getSchemaRegistryVersions(String subject) throws IOException {
        String encodedSubject = java.net.URLEncoder.encode(subject, StandardCharsets.UTF_8);
        String response = makeHttpRequest(schemaRegistryUrl + "/subjects/" + encodedSubject + "/versions", "GET");
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response, List.class);
    }
    
    @SuppressWarnings("unchecked")
    private SchemaInfo getSchemaRegistrySchema(String subject, Integer version) throws IOException {
        String encodedSubject = java.net.URLEncoder.encode(subject, StandardCharsets.UTF_8);
        String response = makeHttpRequest(schemaRegistryUrl + "/subjects/" + encodedSubject + "/versions/" + version, "GET");
        
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schemaData = mapper.readValue(response, Map.class);
        
        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setSchemaId(String.valueOf(schemaData.get("id")));
        schemaInfo.setSchemaName(subject);
        schemaInfo.setVersionNumber(version.longValue());
        schemaInfo.setSchemaDefinition((String) schemaData.get("schema"));
        schemaInfo.setRegistryName("confluent-schema-registry");
        schemaInfo.setDataFormat((String) schemaData.get("schemaType"));
        // Set status to AVAILABLE for active schemas in Confluent Schema Registry
        schemaInfo.setStatus("AVAILABLE");
        
        return schemaInfo;
    }
    
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getApicurioArtifacts() throws IOException {
        String response = makeHttpRequest(schemaRegistryUrl + "/apis/registry/v2/search/artifacts", "GET");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> result = mapper.readValue(response, Map.class);
        return (List<Map<String, Object>>) result.get("artifacts");
    }
    
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getApicurioVersions(String groupId, String artifactId) throws IOException {
        String encodedGroupId = java.net.URLEncoder.encode(groupId != null ? groupId : "default", StandardCharsets.UTF_8);
        String encodedArtifactId = java.net.URLEncoder.encode(artifactId, StandardCharsets.UTF_8);
        String response = makeHttpRequest(schemaRegistryUrl + "/apis/registry/v2/groups/" + encodedGroupId + 
                                        "/artifacts/" + encodedArtifactId + "/versions", "GET");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> result = mapper.readValue(response, Map.class);
        return (List<Map<String, Object>>) result.get("versions");
    }
    
    @SuppressWarnings("unchecked")
    private SchemaInfo getApicurioSchema(String groupId, String artifactId, Map<String, Object> versionMeta) throws IOException {
        String encodedGroupId = java.net.URLEncoder.encode(groupId != null ? groupId : "default", StandardCharsets.UTF_8);
        String encodedArtifactId = java.net.URLEncoder.encode(artifactId, StandardCharsets.UTF_8);
        String version = String.valueOf(versionMeta.get("version"));
        
        String response = makeHttpRequest(schemaRegistryUrl + "/apis/registry/v2/groups/" + encodedGroupId + 
                                        "/artifacts/" + encodedArtifactId + "/versions/" + version, "GET");
        
        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setSchemaId(artifactId);
        schemaInfo.setSchemaName(artifactId);
        schemaInfo.setVersionNumber(Long.valueOf(version));
        schemaInfo.setSchemaDefinition(response);
        schemaInfo.setRegistryName("apicurio-registry");
        schemaInfo.setDataFormat((String) versionMeta.get("type"));
        schemaInfo.setCreatedTime((String) versionMeta.get("createdOn"));
        // Set status to AVAILABLE for active schemas in Apicurio Registry
        schemaInfo.setStatus("AVAILABLE");
        
        return schemaInfo;
    }

    /**
     * Create output directory structure for generated JSON files
     */
    private void createOutputDirectories() {
        java.io.File mskDir = new java.io.File("generated_jsons/msk_jsons");
        if (!mskDir.exists()) {
            mskDir.mkdirs();
            logger.info("Created output directory: generated_jsons/msk_jsons");
        }
        
        java.io.File ccDir = new java.io.File("generated_jsons/cc_jsons");
        if (!ccDir.exists()) {
            ccDir.mkdirs();
            logger.info("Created output directory: generated_jsons/cc_jsons");
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
            // Note: output-file option is ignored - files are automatically saved to generated_jsons/ folder automatically
            String securityProtocol = cmd.getOptionValue("security-protocol", "SSL");
            String saslMechanism = cmd.getOptionValue("sasl-mechanism");
            String saslUsername = cmd.getOptionValue("sasl-username");
            String saslPassword = cmd.getOptionValue("sasl-password");
            String sourceOfSchemas = cmd.getOptionValue("source-of-schemas");
            String schemaRegistryUrl = cmd.getOptionValue("schema-registry-url");
            String schemaRegistryAuthType = cmd.getOptionValue("schema-registry-auth-type", "none");
            String schemaRegistryUsername = cmd.getOptionValue("schema-registry-username");
            String schemaRegistryPassword = cmd.getOptionValue("schema-registry-password");
            String schemaRegistryApiKey = cmd.getOptionValue("schema-registry-api-key");
            String schemaRegistryApiSecret = cmd.getOptionValue("schema-registry-api-secret");
            String schemaRegistryToken = cmd.getOptionValue("schema-registry-token");
            String schemaRegistrySslKeystore = cmd.getOptionValue("schema-registry-ssl-keystore");
            String schemaRegistrySslKeystorePassword = cmd.getOptionValue("schema-registry-ssl-keystore-password");
            String schemaRegistrySslTruststore = cmd.getOptionValue("schema-registry-ssl-truststore");
            String schemaRegistrySslTruststorePassword = cmd.getOptionValue("schema-registry-ssl-truststore-password");
            boolean noMetadata = cmd.hasOption("no-metadata");
            boolean verbose = cmd.hasOption("verbose");
            
            if (clusterArn == null) {
                System.err.println("Error: --cluster-arn is required");
                printHelp(options);
                System.exit(1);
            }
            
            if (sourceOfSchemas == null) {
                System.err.println("Error: --source-of-schemas is required");
                System.err.println("Valid values: glue, schemaregistry, Apicurio, none");
                printHelp(options);
                System.exit(1);
            }
            
            // Validate source-of-schemas value
            if (!Arrays.asList("glue", "schemaregistry", "Apicurio", "none").contains(sourceOfSchemas)) {
                System.err.println("Error: Invalid value for --source-of-schemas: " + sourceOfSchemas);
                System.err.println("Valid values: glue, schemaregistry, Apicurio, none");
                System.exit(1);
            }
            
            // Validate schema registry configuration if needed
            if (("schemaregistry".equals(sourceOfSchemas) || "Apicurio".equals(sourceOfSchemas)) && 
                (schemaRegistryUrl == null || schemaRegistryUrl.trim().isEmpty())) {
                System.err.println("Error: --schema-registry-url is required when using --source-of-schemas " + sourceOfSchemas);
                System.err.println("Please provide the Schema Registry URL (e.g., http://localhost:8081)");
                System.exit(1);
            }
            
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }
            
            MSKACLExtractor extractor = new MSKACLExtractor(clusterArn, region, schemaRegistryUrl, 
                                                                      schemaRegistryAuthType, schemaRegistryUsername, schemaRegistryPassword,
                                                                      schemaRegistryApiKey, schemaRegistryApiSecret, schemaRegistryToken,
                                                                      schemaRegistrySslKeystore, schemaRegistrySslKeystorePassword,
                                                                      schemaRegistrySslTruststore, schemaRegistrySslTruststorePassword);
            
            try {
                logger.info("Connecting to MSK cluster...");
                extractor.getClusterInfo();
                extractor.connectToCluster(securityProtocol, saslMechanism, saslUsername, saslPassword);
                
                logger.info("Exporting ACLs, Topics, and Schemas...");
                extractor.exportACLsAndTopicsToJSON(!noMetadata, sourceOfSchemas);
                
                System.out.println("✅ Successfully exported ACLs to generated_jsons/msk_jsons/msk_acls.json");
                System.out.println("✅ Successfully exported Topics to generated_jsons/msk_jsons/msk_topics.json");
                System.out.println("✅ Successfully exported Consumer Groups to generated_jsons/msk_jsons/msk_consumer_groups.json");
                System.out.println("✅ Successfully exported Schemas to generated_jsons/msk_jsons/msk_schemas.json");
                
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
                .longOpt("source-of-schemas")
                .hasArg()
                .required()
                .desc("Source of schemas to extract from. Valid values: glue, schemaregistry, Apicurio, none")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-url")
                .hasArg()
                .desc("URL of the Schema Registry (required for schemaregistry/Apicurio sources)")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-auth-type")
                .hasArg()
                .desc("Authentication type: none, basic, apikey, bearer, mtls (default: none)")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-username")
                .hasArg()
                .desc("Username for basic authentication")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-password")
                .hasArg()
                .desc("Password for basic authentication")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-api-key")
                .hasArg()
                .desc("API key for authentication")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-api-secret")
                .hasArg()
                .desc("API secret for authentication")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-token")
                .hasArg()
                .desc("Bearer token for authentication")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-ssl-keystore")
                .hasArg()
                .desc("Path to SSL keystore file for mTLS")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-ssl-keystore-password")
                .hasArg()
                .desc("Password for SSL keystore")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-ssl-truststore")
                .hasArg()
                .desc("Path to SSL truststore file for mTLS")
                .build());
        
        options.addOption(org.apache.commons.cli.Option.builder()
                .longOpt("schema-registry-ssl-truststore-password")
                .hasArg()
                .desc("Password for SSL truststore")
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