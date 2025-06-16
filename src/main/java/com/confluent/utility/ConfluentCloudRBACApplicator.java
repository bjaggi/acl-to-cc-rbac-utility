package com.confluent.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.confluent.utility.StatusIcons.*;

/**
 * Confluent Cloud RBAC Applicator using Java API
 * Applies role bindings and creates service accounts using HTTP API calls
 */
public class ConfluentCloudRBACApplicator {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfluentCloudRBACApplicator.class);
    
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String apiSecret;
    private final boolean dryRun;
    private final boolean verbose;
    
    // Statistics
    private int serviceAccountsCreated = 0;
    private int serviceAccountsSkipped = 0;
    private int roleBindingsCreated = 0;
    private int roleBindingsFailed = 0;
    
    // Map to store service account name -> ID mappings
    private Map<String, String> serviceAccountNameToId = new HashMap<>();
    
    public ConfluentCloudRBACApplicator(String restUrl, String apiKey, String apiSecret, boolean dryRun, boolean verbose) {
        // For RBAC operations, always use the Confluent Cloud API, not the Kafka REST Proxy
        this.baseUrl = "https://api.confluent.cloud";
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.dryRun = dryRun;
        this.verbose = verbose;
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
                
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        
        logger.info("Confluent Cloud RBAC Applicator initialized (dry-run: {})", dryRun);
    }
    
    /**
     * Apply RBAC from JSON file
     */
    public void applyRBACFromFile(String rbacFilePath) throws ConfluentCloudException {
        applyRBACFromFile(rbacFilePath, false);
    }
    
    /**
     * Apply RBAC from JSON file with option to skip service accounts
     */
    public void applyRBACFromFile(String rbacFilePath, boolean skipServiceAccounts) throws ConfluentCloudException {
        try {
            // Read the RBAC JSON file
            ACLToRBACConverter.ConfluentCloudRBACOutput rbacData = objectMapper.readValue(
                new File(rbacFilePath), ACLToRBACConverter.ConfluentCloudRBACOutput.class);
            
            logger.info("{} Starting RBAC application from file: {}", PROCESSING, rbacFilePath);
            logger.info("Service Accounts: {}, Role Bindings: {}", 
                       rbacData.service_accounts.size(), rbacData.role_bindings.size());
            
            // Read configuration to get environment and cluster info
            ConfluentCloudConfig config = null;
            try {
                config = readConfig("ccloud.config");
            } catch (IOException e) {
                logger.warn("Could not read ccloud.config: {}", e.getMessage());
            }
            
            // Load service account mappings from cc_service_accounts.json
            loadServiceAccountMappings();
            
            // Create service accounts first (unless skipped)
            if (!skipServiceAccounts) {
                createServiceAccounts(rbacData.service_accounts);
            } else {
                logger.info("{} Skipping service account creation", INFO);
            }
            
            // Apply role bindings
            applyRoleBindings(rbacData.role_bindings, config);
            
            // Print summary
            printSummary();
            
            // Verify role bindings if we have config
            if (config != null) {
                verifyRoleBindings(config.environment, config);
            }
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to read RBAC file: " + rbacFilePath, e);
        }
    }
    
    /**
     * Load service account mappings from cc_service_accounts.json file
     */
    private void loadServiceAccountMappings() {
        // Load service account mappings if available
        String serviceAccountsFile = "generated_jsons/cc_jsons/cc_service_accounts.json";
        File file = new File(serviceAccountsFile);
        
        if (!file.exists()) {
            logger.warn("{} Service accounts file not found: {}", WARNING, serviceAccountsFile);
            logger.warn("     {} Will fall back to API lookup for service account IDs", BULLET);
            return;
        }
        
        try {
            logger.info("{} Loading service account mappings from: {}", PROCESSING, serviceAccountsFile);
            
            // Read the service accounts JSON file
            Map<String, Object> serviceAccountsData = objectMapper.readValue(file, Map.class);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) serviceAccountsData.get("service_accounts");
            
            if (serviceAccounts != null) {
                for (Map<String, Object> sa : serviceAccounts) {
                    String name = (String) sa.get("name");
                    String id = (String) sa.get("id");
                    String status = (String) sa.get("status");
                    
                    // Only use service accounts that were successfully created or already existed
                    if (name != null && id != null && 
                        ("created".equals(status) || "existing".equals(status)) &&
                        !id.equals("dry-run-id")) {
                        serviceAccountNameToId.put(name, id);
                        if (verbose) {
                            logger.debug("Loaded service account mapping: {} -> {}", name, id);
                        }
                    }
                }
                
                logger.info("{} Loaded {} service account mappings from file", SUCCESS, serviceAccountNameToId.size());
            } else {
                logger.warn("{} No service accounts found in file: {}", WARNING, serviceAccountsFile);
            }
            
        } catch (IOException e) {
            logger.warn("{} Failed to load service account mappings from {}: {}", WARNING, serviceAccountsFile, e.getMessage());
            logger.warn("     {} Will fall back to API lookup for service account IDs", BULLET);
        }
    }
    
    /**
     * Create service accounts
     */
    private void createServiceAccounts(List<ACLToRBACConverter.ServiceAccountInfo> serviceAccounts) 
            throws ConfluentCloudException {
        
        if (serviceAccounts.isEmpty()) {
            logger.info("No service accounts to create");
            return;
        }
        
        logger.info("Creating {} service accounts...", serviceAccounts.size());
        
        // Get existing service accounts to avoid duplicates
        Set<String> existingAccounts = getExistingServiceAccounts();
        
        for (ACLToRBACConverter.ServiceAccountInfo sa : serviceAccounts) {
            if (existingAccounts.contains(sa.name)) {
                logger.info("{} Service account '{}' already exists, skipping...", EXISTS, sa.name);
                serviceAccountsSkipped++;
                continue;
            }
            
            if (dryRun) {
                logger.info("{} Would create service account '{}'", DRY_RUN, sa.name);
                continue;
            }
            
            try {
                String serviceAccountId = createServiceAccount(sa);
                if (serviceAccountId != null) {
                    serviceAccountNameToId.put(sa.name, serviceAccountId);
                }
                serviceAccountsCreated++;
                logger.info("{} Created service account: {} (ID: {})", SUCCESS, sa.name, serviceAccountId);
            } catch (ConfluentCloudException e) {
                String errorMsg = e.getMessage();
                if (errorMsg.contains("409")) {
                    logger.error("❌ Failed to create service account '{}': Name already exists", sa.name);
                    logger.error("   This could be because:");
                    logger.error("   • Service account exists but was created by different user/organization");
                    logger.error("   • There's a soft-deleted service account with this name");
                    logger.error("   • The name is reserved by Confluent Cloud");
                    logger.error("   • Try using a different name (e.g., add suffix like '-v2', '-new')");
                } else if (errorMsg.contains("400")) {
                    logger.error("❌ Failed to create service account '{}': Invalid request", sa.name);
                    logger.error("   Check service account name format and description");
                } else if (errorMsg.contains("401") || errorMsg.contains("403")) {
                    logger.error("❌ Failed to create service account '{}': Authentication/Authorization error", sa.name);
                    logger.error("   Verify your Cloud API keys have permission to create service accounts");
                } else {
                    logger.error("❌ Failed to create service account '{}': {}", sa.name, errorMsg);
                }
            }
        }
    }
    
    /**
     * Get service account ID from service account name
     */
    private String getServiceAccountId(String serviceAccountName) throws ConfluentCloudException {
        // Check local cache first (includes mappings from cc_service_accounts.json)
        if (serviceAccountNameToId.containsKey(serviceAccountName)) {
            String cachedId = serviceAccountNameToId.get(serviceAccountName);
            if (verbose) {
                logger.debug("Using cached service account ID for '{}': {}", serviceAccountName, cachedId);
            }
            return cachedId;
        }
        
        // Fall back to API lookup if not found in cache/file
        logger.info("{} Service account '{}' not found in cache, looking up via API...", PROCESSING, serviceAccountName);
        
        Request request = new Request.Builder()
                .url(baseUrl + "/iam/v2/service-accounts")
                .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                .addHeader("Content-Type", "application/json")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ConfluentCloudException("Failed to list service accounts: " + response.code());
            }
            
            String responseBody = response.body().string();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> serviceAccounts = (List<Map<String, Object>>) result.get("data");
            
            if (serviceAccounts != null) {
                for (Map<String, Object> sa : serviceAccounts) {
                    String displayName = (String) sa.get("display_name");
                    String id = (String) sa.get("id");
                    if (serviceAccountName.equals(displayName)) {
                        // Cache the result for future use
                        serviceAccountNameToId.put(serviceAccountName, id);
                        logger.info("{} Found service account '{}' via API: {}", SUCCESS, serviceAccountName, id);
                        return id;
                    }
                }
            }
            
            return null; // Service account not found
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to get service account ID", e);
        }
    }

    /**
     * Get existing service accounts
     */
    private Set<String> getExistingServiceAccounts() throws ConfluentCloudException {
        logger.debug("Fetching existing service accounts...");
        
        Request request = new Request.Builder()
                .url(baseUrl + "/iam/v2/service-accounts")
                .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                .addHeader("Content-Type", "application/json")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ConfluentCloudException("Failed to fetch service accounts: " + response.code());
            }
            
            String responseBody = response.body().string();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            
            Set<String> existingNames = new HashSet<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.get("data");
            
            if (accounts != null) {
                for (Map<String, Object> account : accounts) {
                    String name = (String) account.get("display_name");
                    if (name != null) {
                        existingNames.add(name);
                    }
                }
            }
            
            logger.debug("Found {} existing service accounts", existingNames.size());
            return existingNames;
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to parse service accounts response", e);
        }
    }
    
    /**
     * Create a single service account
     */
    private String createServiceAccount(ACLToRBACConverter.ServiceAccountInfo sa) throws ConfluentCloudException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("display_name", sa.name);
        requestBody.put("description", sa.description);
        
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            logger.info("Service account creation request: {}", jsonBody);
            
            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(baseUrl + "/iam/v2/service-accounts")
                    .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                
                logger.info("Service account creation response: {} - {}", response.code(), responseBody);
                
                if (!response.isSuccessful()) {
                    throw new ConfluentCloudException("Failed to create service account: " + response.code() + " - " + responseBody);
                }
                
                // Parse the response to get the service account ID
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                    String serviceAccountId = (String) result.get("id");
                    
                    if (verbose) {
                        logger.debug("Service account created successfully: {}", responseBody);
                    }
                    
                    return serviceAccountId;
                } catch (Exception e) {
                    logger.warn("Could not parse service account ID from response: {}", e.getMessage());
                    return null;
                }
            }
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to create service account", e);
        }
    }
    
    /**
     * Apply role bindings
     */
    private void applyRoleBindings(List<ACLToRBACConverter.ConfluentCloudRoleBinding> roleBindings, 
                                  ConfluentCloudConfig config) throws ConfluentCloudException {
        
        if (roleBindings.isEmpty()) {
            logger.info("No role bindings to apply");
            return;
        }
        
        logger.info("Applying {} role bindings...", roleBindings.size());
        
        for (int i = 0; i < roleBindings.size(); i++) {
            ACLToRBACConverter.ConfluentCloudRoleBinding binding = roleBindings.get(i);
            
            logger.info("[{}/{}] Creating role binding: {} -> {} on {}:{}", 
                       i + 1, roleBindings.size(), binding.principal, binding.role, 
                       binding.resource_type, binding.resource_name);
            
            if (dryRun) {
                logger.info("  {} Would create role binding for {}", DRY_RUN, binding.principal);
                continue;
            }
            
            try {
                createRoleBinding(binding, config);
                roleBindingsCreated++;
                logger.info("  {} Success", SUCCESS);
            } catch (ConfluentCloudException e) {
                roleBindingsFailed++;
                String errorMsg = e.getMessage();
                if (errorMsg.contains("Service account not found")) {
                    logger.error("  {} Failed: Service account '{}' not found", ERROR, binding.principal);
                    logger.error("     {} Check if service account was created successfully", BULLET);
                    logger.error("     {} Verify service account name matches exactly", BULLET);
                    logger.error("     {} Try running without --skip-service-accounts first", BULLET);
                } else if (errorMsg.contains("400")) {
                    logger.error("  {} Failed: Bad Request (400)", ERROR);
                    logger.error("     {} MOST LIKELY: Cloud API keys lack OrganizationAdmin/EnvironmentAdmin permissions", BULLET);
                    logger.error("     {} Cloud API keys can list service accounts but cannot create role bindings", BULLET);
                    logger.error("     {} Contact your Confluent Cloud admin to grant OrganizationAdmin role", BULLET);
                    logger.error("     {} Alternative checks:", BULLET);
                    logger.error("       {} Check if topic '{}' exists in cluster '{}'", BULLET, binding.resource_name, binding.cluster_id);
                    logger.error("       {} Verify environment '{}' and cluster '{}' are correct", BULLET, binding.environment, binding.cluster_id);
                    logger.error("       {} Check if role '{}' is valid for resource type '{}'", BULLET, binding.role, binding.resource_type);
                } else if (errorMsg.contains("401") || errorMsg.contains("403")) {
                    logger.error("  {} Failed: Authentication/Authorization error", ERROR);
                    logger.error("     {} Verify your Cloud API keys have RBAC permissions", BULLET);
                    logger.error("     {} Check if you have access to environment '{}'", BULLET, binding.environment);
                } else if (errorMsg.contains("409")) {
                    logger.error("  {} Failed: Role binding already exists", WARNING);
                    logger.info("     {} This role binding may already be configured", INFO);
                } else {
                    logger.error("  {} Failed: {}", ERROR, errorMsg);
                }
            }
        }
    }
    
    /**
     * Create a single role binding
     */
    private void createRoleBinding(ACLToRBACConverter.ConfluentCloudRoleBinding binding, 
                                  ConfluentCloudConfig config) throws ConfluentCloudException {
        
        // Get service account ID from name
        String serviceAccountId = getServiceAccountId(binding.principal);
        if (serviceAccountId == null) {
            throw new ConfluentCloudException("Service account not found: " + binding.principal);
        }
        
        // Log the principal mapping for transparency
        logger.info("     {} Principal mapping: '{}' -> 'User:{}'", BULLET, binding.principal, serviceAccountId);
        
        // Build CRN pattern based on resource type
        String crnPattern;
        if (config != null && config.organization != null) {
            String orgId = config.organization;
            String envId = binding.environment;
            String clusterId = binding.cluster_id;
            
            if (binding.resource_type.toLowerCase().equals("cluster")) {
                // For cluster-level permissions
                crnPattern = String.format("crn://confluent.cloud/organization=%s/environment=%s/cloud-cluster=%s/kafka=%s",
                    orgId, envId, clusterId, clusterId);
            } else {
                // For topic-level permissions
                crnPattern = String.format("crn://confluent.cloud/organization=%s/environment=%s/cloud-cluster=%s/kafka=%s/topic=%s",
                    orgId, envId, clusterId, clusterId, binding.resource_name);
            }
        } else {
            throw new ConfluentCloudException("Missing organization ID in configuration - required for CRN pattern");
        }
        
        // Build request body for role binding
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("principal", "User:" + serviceAccountId);
        requestBody.put("role_name", binding.role);
        requestBody.put("crn_pattern", crnPattern);
        
        if (verbose) {
            logger.info("     {} Role binding request body:", BULLET);
            logger.info("       {} principal: User:{}", BULLET, serviceAccountId);
            logger.info("       {} role_name: {}", BULLET, binding.role);
            logger.info("       {} crn_pattern: {}", BULLET, crnPattern);
        }
        
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            if (verbose) {
                logger.info("Role binding request body: {}", jsonBody);
            }
            
            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));
            Request request = new Request.Builder()
                    .url(baseUrl + "/iam/v2/role-bindings")
                    .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error details";
                    throw new ConfluentCloudException("Failed to create role binding: " + response.code() + " - " + errorBody);
                }
                
                if (verbose) {
                    String responseBody = response.body().string();
                    logger.debug("Role binding created: {}", responseBody);
                }
            }
            
        } catch (IOException e) {
            throw new ConfluentCloudException("Failed to create role binding", e);
        }
    }
    
    /**
     * Verify role bindings
     */
    public void verifyRoleBindings(String environment, ConfluentCloudConfig config) throws ConfluentCloudException {
        if (dryRun) {
            logger.info("{} Would verify role bindings for environment: {}", DRY_RUN, environment);
            return;
        }
        
        logger.info("{} Verifying role bindings for environment: {}", PROCESSING, environment);
        
        // Use CRN pattern to list role bindings (requires organization ID)
        String crnPattern = String.format("crn://confluent.cloud/organization=%s/*", 
            config != null && config.organization != null ? config.organization : "*");
        
        Request request = new Request.Builder()
                .url(baseUrl + "/iam/v2/role-bindings?crn_pattern=" + crnPattern)
                .addHeader("Authorization", Credentials.basic(apiKey, apiSecret))
                .addHeader("Content-Type", "application/json")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("Could not verify role bindings (permissions may be limited): {}", response.code());
                return;
            }
            
            String responseBody = response.body().string();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> roleBindings = (List<Map<String, Object>>) result.get("data");
            int totalBindings = roleBindings != null ? roleBindings.size() : 0;
            
            logger.info("{} Role bindings verification completed", SUCCESS);
            logger.info("{} Total role bindings in environment: {}", STATS, totalBindings);
            
        } catch (IOException e) {
            logger.warn("Could not parse role bindings verification response: {}", e.getMessage());
        }
    }
    
    /**
     * Print application summary
     */
    private void printSummary() {
        logger.info("{} RBAC Application Summary:", SUMMARY);
        logger.info("========================");
        logger.info(formatSummaryLine("Service Accounts Created", serviceAccountsCreated, "success"));
        logger.info(formatSummaryLine("Service Accounts Skipped", serviceAccountsSkipped, "skipped"));
        logger.info(formatSummaryLine("Role Bindings Created", roleBindingsCreated, "success"));
        logger.info(formatSummaryLine("Role Bindings Failed", roleBindingsFailed, "failed"));
        
        if (roleBindingsFailed > 0) {
            logger.error("{} Some role bindings failed. Check logs for details.", ERROR);
        } else if (!dryRun) {
            logger.info("{} All operations completed successfully!", SUCCESS);
        }
    }
    
    /**
     * Read configuration from properties file
     */
    public static ConfluentCloudConfig readConfig(String configFilePath) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFilePath)) {
            props.load(fis);
        }
        
        ConfluentCloudConfig config = new ConfluentCloudConfig();
        config.environment = props.getProperty("confluent.cloud.environment");
        config.cluster = props.getProperty("confluent.cloud.cluster");
        config.organization = props.getProperty("confluent.cloud.organization");
        
        // Cloud API keys for RBAC/IAM operations
        // Support multiple property name formats for Cloud API keys
        String cloudApiKey = props.getProperty("cloud.api.key");
        if (cloudApiKey == null) {
            cloudApiKey = props.getProperty("confluent_cloud_key");
        }
        String cloudApiSecret = props.getProperty("cloud.api.secret");
        if (cloudApiSecret == null) {
            cloudApiSecret = props.getProperty("confluent_cloud_secret");
        }
        
        // Kafka API keys for topic operations
        String kafkaApiKey = props.getProperty("sasl.username");
        String kafkaApiSecret = props.getProperty("sasl.password");
        
        // Set the keys
        config.apiKey = cloudApiKey;
        config.apiSecret = cloudApiSecret;
        config.kafkaApiKey = kafkaApiKey;
        config.kafkaApiSecret = kafkaApiSecret;
        
        config.bootstrapServers = props.getProperty("bootstrap.servers");
        config.restUrl = props.getProperty("cloud.rest.url", "https://api.confluent.cloud");
        
        // Validate required properties
        if (config.environment == null || config.cluster == null) {
            throw new IllegalArgumentException("Missing required environment or cluster configuration");
        }
        
        // Validate that we have Cloud API keys for RBAC operations
        if (config.apiKey == null || config.apiSecret == null) {
            throw new IllegalArgumentException("Missing Cloud API keys (confluent_cloud_key/confluent_cloud_secret or cloud.api.key/cloud.api.secret)");
        }
        
        // Validate that we have Kafka API keys for topic operations
        if (config.kafkaApiKey == null || config.kafkaApiSecret == null) {
            throw new IllegalArgumentException("Missing Kafka API keys (sasl.username/sasl.password)");
        }
        
        return config;
    }
    
    /**
     * Configuration class
     */
    public static class ConfluentCloudConfig {
        public String environment;
        public String cluster;
        public String organization;    // Organization ID for CRN patterns
        public String apiKey;          // Cloud API key for RBAC operations
        public String apiSecret;       // Cloud API secret for RBAC operations
        public String kafkaApiKey;     // Kafka API key for topic operations
        public String kafkaApiSecret;  // Kafka API secret for topic operations
        public String bootstrapServers;
        public String restUrl;
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
        
        // Parse flags first
        boolean dryRun = Arrays.asList(args).contains("--dry-run");
        boolean verbose = Arrays.asList(args).contains("--verbose");
        boolean skipServiceAccounts = Arrays.asList(args).contains("--skip-service-accounts");
        
        // Parse positional arguments (skip flags)
        List<String> positionalArgs = new ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                positionalArgs.add(arg);
            }
        }
        
        String rbacFile = positionalArgs.size() > 0 ? positionalArgs.get(0) : "generated_jsons/cc_jsons/cc_rbac.json";
        String configFile = positionalArgs.size() > 1 ? positionalArgs.get(1) : "ccloud.config";
        
        try {
            // Read configuration
            ConfluentCloudConfig config = readConfig(configFile);
            
            // Create applicator
            ConfluentCloudRBACApplicator applicator = new ConfluentCloudRBACApplicator(
                config.restUrl, config.apiKey, config.apiSecret, dryRun, verbose);
            
            // Apply RBAC
            applicator.applyRBACFromFile(rbacFile, skipServiceAccounts);
            
            // Verify results
            applicator.verifyRoleBindings(config.environment, config);
            
            if (dryRun) {
                System.out.println(DRY_RUN + " Dry run completed. Remove --dry-run to apply changes.");
            } else {
                System.out.println(COMPLETED + " RBAC application completed successfully!");
                System.out.println(ARROW + " Next steps:");
                System.out.println("  " + BULLET + " Verify role bindings in Confluent Cloud console");
                System.out.println("  " + BULLET + " Test application access with new permissions");
                System.out.println("  " + BULLET + " Update applications with new service account credentials");
            }
            
        } catch (Exception e) {
            logger.error("{} RBAC application failed: {}", ERROR, e.getMessage(), e);
            System.err.println(ERROR + " RBAC application failed: " + e.getMessage());
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("Confluent Cloud RBAC Applicator");
        System.out.println("Usage: java ConfluentCloudRBACApplicator [rbac_file] [config_file] [options]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  rbac_file    RBAC JSON file (default: generated_jsons/cc_jsons/cc_rbac.json)");
        System.out.println("  config_file  Confluent Cloud config file (default: ccloud.config)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run               Show what would be done without making changes");
        System.out.println("  --verbose               Enable verbose logging");
        System.out.println("  --skip-service-accounts Skip service account creation");
        System.out.println("  --help                  Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java ConfluentCloudRBACApplicator");
        System.out.println("  java ConfluentCloudRBACApplicator --dry-run");
        System.out.println("  java ConfluentCloudRBACApplicator my_rbac.json my_config.properties --verbose");
    }
} 