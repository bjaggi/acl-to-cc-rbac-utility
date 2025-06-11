package com.confluent.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.cli.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeAclsResult;
import org.apache.kafka.common.acl.*;
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
    private AdminClient adminClient;
    private ClusterInfo clusterInfo;

    public MSKACLExtractor(String clusterArn, String region) {
        this.clusterArn = clusterArn;
        this.region = region;
        this.mskClient = KafkaClient.builder()
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
     * Get bootstrap servers from cluster info
     */
    public String getBootstrapServers() throws MSKACLExtractorException {
        try {
            GetBootstrapBrokersRequest request = GetBootstrapBrokersRequest.builder()
                    .clusterArn(clusterArn)
                    .build();
            
            GetBootstrapBrokersResponse response = mskClient.getBootstrapBrokers(request);
            
            // Try to get TLS bootstrap servers first, then others
            String bootstrapServers = response.bootstrapBrokerStringTls();
            if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                bootstrapServers = response.bootstrapBrokerString();
            }
            if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                bootstrapServers = response.bootstrapBrokerStringSaslScram();
            }
            if (bootstrapServers == null || bootstrapServers.isEmpty()) {
                bootstrapServers = response.bootstrapBrokerStringSaslIam();
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
        String bootstrapServers = getBootstrapServers();
        
        Properties config = new Properties();
        config.put("bootstrap.servers", bootstrapServers);
        config.put("security.protocol", securityProtocol);
        config.put("client.id", "msk-acl-extractor");
        
        // Configure SSL
        if ("SSL".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol)) {
            config.put("ssl.truststore.location", System.getProperty("javax.net.ssl.trustStore", ""));
            config.put("ssl.truststore.password", System.getProperty("javax.net.ssl.trustStorePassword", ""));
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
     * Export ACLs to a JSON file
     */
    public void exportACLsToJSON(String outputFile, boolean includeMetadata) throws MSKACLExtractorException {
        List<ACLBinding> acls = listACLs();
        
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("acls", acls);
        exportData.put("count", acls.size());
        exportData.put("exported_at", LocalDateTime.now().toString());
        
        if (includeMetadata && clusterInfo != null) {
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
            
            exportData.put("cluster_metadata", metadata);
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            
            try (FileWriter writer = new FileWriter(outputFile)) {
                mapper.writeValue(writer, exportData);
            }
            
            logger.info("Successfully exported {} ACLs to {}", acls.size(), outputFile);
            
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
            String outputFile = cmd.getOptionValue("output-file", "msk_acls.json");
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
                
                logger.info("Exporting ACLs...");
                extractor.exportACLsToJSON(outputFile, !noMetadata);
                
                System.out.println("✅ Successfully exported ACLs to " + outputFile);
                
            } finally {
                extractor.close();
            }
            
        } catch (ParseException e) {
            System.err.println("Error parsing command line: " + e.getMessage());
            printHelp(options);
            System.exit(1);
        } catch (MSKACLExtractorException e) {
            logger.error("Error: {}", e.getMessage());
            System.err.println("❌ Failed to export ACLs: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Options createOptions() {
        Options options = new Options();
        
        options.addOption(Option.builder()
                .longOpt("cluster-arn")
                .hasArg()
                .required()
                .desc("ARN of the MSK cluster")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("region")
                .hasArg()
                .desc("AWS region (default: us-east-1)")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("output-file")
                .hasArg()
                .desc("Output JSON file (default: msk_acls.json)")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("security-protocol")
                .hasArg()
                .desc("Security protocol: SSL, SASL_SSL, PLAINTEXT, SASL_PLAINTEXT (default: SSL)")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("sasl-mechanism")
                .hasArg()
                .desc("SASL mechanism: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, AWS_MSK_IAM")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("sasl-username")
                .hasArg()
                .desc("SASL username if required")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("sasl-password")
                .hasArg()
                .desc("SASL password if required")
                .build());
        
        options.addOption(Option.builder()
                .longOpt("no-metadata")
                .desc("Exclude cluster metadata from export")
                .build());
        
        options.addOption(Option.builder("v")
                .longOpt("verbose")
                .desc("Enable verbose logging")
                .build());
        
        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Show this help message")
                .build());
        
        return options;
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar acl-to-cc-rbac-utility.jar", 
                "Amazon MSK ACL Extractor\n\n" +
                "Connects to an Amazon MSK cluster and exports all ACLs to a JSON file.\n\n" +
                "Example usage:\n" +
                "java -jar acl-to-cc-rbac-utility.jar --cluster-arn arn:aws:kafka:us-east-1:123456789012:cluster/my-cluster/abc-123\n\n" +
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