package com.confluent.utility;

/**
 * Unified MSK to Confluent Cloud Migration Utility
 * 
 * This utility provides three main operations:
 * 1. extract - Extract ACLs, topics, and schemas from MSK
 * 2. convert - Convert MSK ACLs to Confluent Cloud RBAC format
 * 3. apply   - Apply RBAC role bindings to Confluent Cloud
 */
public class MSKToConfluentCloudUtility {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            System.exit(1);
        }
        
        String command = args[0].toLowerCase();
        String[] commandArgs = new String[args.length - 1];
        System.arraycopy(args, 1, commandArgs, 0, args.length - 1);
        
        try {
            switch (command) {
                case "extract":
                    System.out.println("🔍 Extracting MSK metadata...");
                    MSKACLExtractor.main(commandArgs);
                    break;
                    
                case "convert":
                    System.out.println("🔄 Converting ACLs to RBAC format...");
                    ACLToRBACConverter.main(commandArgs);
                    break;
                    
                case "apply":
                    System.out.println("🚀 Applying RBAC to Confluent Cloud...");
                    ConfluentCloudRBACApplicator.main(commandArgs);
                    break;
                    
                case "create-topics":
                    System.out.println("📝 Creating topics in Confluent Cloud...");
                    ConfluentCloudTopicCreator.main(commandArgs);
                    break;
                    
                case "extract-principals":
                    System.out.println("👥 Extracting principals from MSK ACLs...");
                    MSKPrincipalExtractor.main(commandArgs);
                    break;
                    
                case "create-service-accounts":
                    System.out.println("🔑 Creating service accounts in Confluent Cloud...");
                    ConfluentCloudServiceAccountCreator.main(commandArgs);
                    break;
                    
                case "update-service-account-id":
                    System.out.println("🔧 Updating service account ID...");
                    ServiceAccountIDUpdater.main(commandArgs);
                    break;
                    
                case "help":
                case "--help":
                case "-h":
                    printHelp();
                    break;
                    
                default:
                    System.err.println("❌ Unknown command: " + command);
                    printHelp();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("❌ Error executing command '" + command + "': " + e.getMessage());
            if (System.getProperty("debug") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("MSK to Confluent Cloud Migration Utility");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Usage: java -jar msk-to-confluent-cloud.jar <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  extract            Extract ACLs, topics, and schemas from MSK");
        System.out.println("  convert            Convert MSK ACLs to Confluent Cloud RBAC format");
        System.out.println("  apply              Apply RBAC role bindings to Confluent Cloud");
        System.out.println("  create-topics      Create topics in Confluent Cloud from MSK topics");
        System.out.println("  extract-principals Extract unique principals from MSK ACLs");
        System.out.println("  create-service-accounts Create service accounts in Confluent Cloud from principals");
        System.out.println("  update-service-account-id Update service account ID in JSON files");
        System.out.println("  help               Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Extract metadata from MSK");
        System.out.println("  java -jar msk-to-confluent-cloud.jar extract --config msk.config");
        System.out.println();
        System.out.println("  # Convert ACLs to RBAC format");
        System.out.println("  java -jar msk-to-confluent-cloud.jar convert");
        System.out.println();
        System.out.println("  # Apply RBAC to Confluent Cloud");
        System.out.println("  java -jar msk-to-confluent-cloud.jar apply --config ccloud.config");
        System.out.println();
        System.out.println("  # Create topics in Confluent Cloud");
        System.out.println("  java -jar msk-to-confluent-cloud.jar create-topics --config ccloud.config");
        System.out.println();
        System.out.println("  # Extract principals from MSK ACLs");
        System.out.println("  java -jar msk-to-confluent-cloud.jar extract-principals");
        System.out.println();
        System.out.println("  # Create service accounts in Confluent Cloud");
        System.out.println("  java -jar msk-to-confluent-cloud.jar create-service-accounts --config ccloud.config");
        System.out.println();
        System.out.println("  # Update service account ID manually");
        System.out.println("  java -jar msk-to-confluent-cloud.jar update-service-account-id update jaggi-msk-role sa-abc123");
        System.out.println();
        System.out.println("  # List service accounts with unknown IDs");
        System.out.println("  java -jar msk-to-confluent-cloud.jar update-service-account-id list");
        System.out.println();
        System.out.println("For detailed help on each command, use:");
        System.out.println("  java -jar msk-to-confluent-cloud.jar <command> --help");
        System.out.println();
        System.out.println("Add -Ddebug=true for detailed error messages");
    }
} 