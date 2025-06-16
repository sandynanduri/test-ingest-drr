package com.regnosys.drr.examples.util;

import cdm.base.staticdata.identifier.TradeIdentifierTypeEnum;
import cdm.base.staticdata.party.Party;
import cdm.event.common.*;
import cdm.event.workflow.WorkflowStep;
import cdm.product.template.TradableProduct;
import drr.regulation.common.ReportableEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import com.rosetta.model.lib.RosettaModelObject;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive CDM Structure Validator for DRR
 * 
 * This utility validates input JSON files to ensure they meet CDM structure requirements
 * and DRR-specific needs. It provides detailed diagnostics and suggestions for fixing issues.
 */
public class CDMStructureValidator {

    private static final ObjectMapper rosettaMapper = RosettaObjectMapper.getNewRosettaObjectMapper();
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java CDMStructureValidator <path-to-json-file>");
            System.out.println("Example: java CDMStructureValidator result-json-files/fpml-5-10/products/rates/your-file.json");
            return;
        }
        
        String filePath = args[0];
        CDMStructureValidator validator = new CDMStructureValidator();
        validator.validateFile(filePath);
    }

    public void validateFile(String filePath) {
        System.out.println("=== CDM Structure Validator for DRR ===");
        System.out.println("Validating file: " + filePath);
        System.out.println("=" + "=".repeat(60));
        
        try {
            // Step 1: Basic JSON validation
            JsonNode jsonNode = loadAndParseJson(filePath);
            if (jsonNode == null) return;
            
            // Step 2: Identify file type and structure
            String detectedType = detectFileType(jsonNode);
            System.out.println("✓ Detected file type: " + detectedType);
            
            // Step 3: Try loading as different CDM types
            validateCDMTypes(filePath);
            
            // Step 4: DRR-specific validations
            validateForDRR(filePath, detectedType);
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("✓ Validation completed. See diagnostics above.");
            
        } catch (Exception e) {
            System.err.println("✗ Fatal error during validation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private JsonNode loadAndParseJson(String filePath) {
        try {
            URL url = Resources.getResource(filePath);
            String json = Resources.toString(url, StandardCharsets.UTF_8);
            
            JsonNode jsonNode = jsonMapper.readTree(json);
            System.out.println("✓ JSON is well-formed");
            System.out.println("  File size: " + json.length() + " characters");
            System.out.println("  Root fields: " + jsonNode.size());
            
            // Show root field names for debugging
            List<String> fieldNames = new ArrayList<>();
            jsonNode.fieldNames().forEachRemaining(fieldNames::add);
            System.out.println("  Root field names: " + String.join(", ", fieldNames));
            
            return jsonNode;
            
        } catch (IOException e) {
            System.err.println("✗ Failed to load or parse JSON file: " + e.getMessage());
            return null;
        }
    }

    private String detectFileType(JsonNode jsonNode) {
        System.out.println("\n--- File Type Detection ---");
        
        if (jsonNode.has("originatingWorkflowStep")) {
            System.out.println("  → Contains 'originatingWorkflowStep' - likely ReportableEvent");
            return "ReportableEvent";
        }
        
        if (jsonNode.has("businessEvent")) {
            System.out.println("  → Contains 'businessEvent' - likely WorkflowStep");
            return "WorkflowStep";
        }
        
        if (jsonNode.has("trade") && !jsonNode.has("state")) {
            System.out.println("  → Contains 'trade' at root level - likely Trade object");
            return "Trade";
        }
        
        if (jsonNode.has("trade") && jsonNode.has("state")) {
            System.out.println("  → Contains 'trade' and 'state' - likely TradeState");
            return "TradeState";
        }
        
        if (jsonNode.has("instruction")) {
            System.out.println("  → Contains 'instruction' - likely WorkflowStep or EventInstruction");
            return "WorkflowStep/EventInstruction";
        }
        
        if (jsonNode.has("proposedEvent")) {
            System.out.println("  → Contains 'proposedEvent' - likely WorkflowStep");
            return "WorkflowStep";
        }
        
        System.out.println("  → Unknown structure - manual inspection needed");
        List<String> fieldNames = new ArrayList<>();
        jsonNode.fieldNames().forEachRemaining(fieldNames::add);
        System.out.println("  → Root fields: " + String.join(", ", fieldNames));
        return "Unknown";
    }

    private void validateCDMTypes(String filePath) {
        System.out.println("\n--- CDM Type Validation ---");
        
        tryLoadAs(TradeState.class, filePath, "TradeState");
        tryLoadAs(Trade.class, filePath, "Trade");
        tryLoadAs(ReportableEvent.class, filePath, "ReportableEvent");
        tryLoadAs(WorkflowStep.class, filePath, "WorkflowStep");
        tryLoadAs(BusinessEvent.class, filePath, "BusinessEvent");
    }

    private <T extends RosettaModelObject> boolean tryLoadAs(Class<T> clazz, String filePath, String typeName) {
        try {
            T object = ResourcesUtils.getObject(clazz, filePath);
            System.out.println("✓ Successfully loaded as " + typeName);
            
            try {
                T resolved = ResourcesUtils.getObjectAndResolveReferences(clazz, filePath);
                System.out.println("✓ Reference resolution successful for " + typeName);
                validateSpecificType(resolved, typeName);
                return true;
                
            } catch (Exception e) {
                System.out.println("⚠ Reference resolution failed for " + typeName + ": " + e.getMessage());
                validateSpecificType(object, typeName);
                return true;
            }
            
        } catch (Exception e) {
            System.out.println("✗ Failed to load as " + typeName + ": " + e.getMessage());
            return false;
        }
    }

    private void validateSpecificType(RosettaModelObject object, String typeName) {
        System.out.println("  → Validating " + typeName + " structure:");
        
        switch (typeName) {
            case "TradeState":
                validateTradeState((TradeState) object);
                break;
            case "Trade":
                validateTrade((Trade) object);
                break;
            case "ReportableEvent":
                validateReportableEvent((ReportableEvent) object);
                break;
            case "WorkflowStep":
                validateWorkflowStep((WorkflowStep) object);
                break;
            case "BusinessEvent":
                validateBusinessEvent((BusinessEvent) object);
                break;
        }
    }

    private void validateTradeState(TradeState tradeState) {
        if (tradeState.getTrade() == null) {
            System.out.println("    ✗ CRITICAL: TradeState.getTrade() is null");
            System.out.println("    → This is the root cause of your null pointer exception");
            System.out.println("    → Check if 'trade' field exists in JSON and is properly structured");
        } else {
            System.out.println("    ✓ Trade object exists");
            validateTrade(tradeState.getTrade());
        }
        
        if (tradeState.getState() == null) {
            System.out.println("    ⚠ State is null (may be optional depending on use case)");
        } else {
            System.out.println("    ✓ State exists: " + tradeState.getState().getPositionState());
        }
    }

    private void validateTrade(Trade trade) {
        System.out.println("    → Validating Trade structure:");
        
        if (trade.getTradeIdentifier() == null || trade.getTradeIdentifier().isEmpty()) {
            System.out.println("      ✗ CRITICAL: No trade identifiers found");
            System.out.println("      → DRR requires trade identifiers for event processing");
        } else {
            System.out.println("      ✓ Trade identifiers found: " + trade.getTradeIdentifier().size());
            validateTradeIdentifiers(trade.getTradeIdentifier());
        }
        
        if (trade.getTradeDate() == null) {
            System.out.println("      ⚠ Trade date is null");
        } else {
            System.out.println("      ✓ Trade date: " + trade.getTradeDate().getValue());
        }
        
        if (trade.getTradableProduct() == null) {
            System.out.println("      ✗ CRITICAL: Tradable product is null");
        } else {
            System.out.println("      ✓ Tradable product exists");
            validateTradableProduct(trade.getTradableProduct());
        }
        
        if (trade.getParty() == null || trade.getParty().isEmpty()) {
            System.out.println("      ✗ CRITICAL: No parties found");
            System.out.println("      → DRR requires parties for reporting");
        } else {
            System.out.println("      ✓ Parties found: " + trade.getParty().size());
            validateParties(trade.getParty());
        }
    }

    private void validateTradeIdentifiers(List<? extends TradeIdentifier> identifiers) {
        for (int i = 0; i < identifiers.size(); i++) {
            TradeIdentifier id = identifiers.get(i);
            System.out.println("        Identifier " + (i + 1) + ":");
            
            if (id.getAssignedIdentifier() == null || id.getAssignedIdentifier().isEmpty()) {
                System.out.println("          ✗ No assigned identifiers");
            } else {
                System.out.println("          ✓ Assigned identifiers: " + id.getAssignedIdentifier().size());
                
                if (id.getIdentifierType() == TradeIdentifierTypeEnum.UNIQUE_TRANSACTION_IDENTIFIER) {
                    System.out.println("          ✓ UTI found");
                }
            }
        }
    }

    private void validateTradableProduct(TradableProduct tradableProduct) {
        if (tradableProduct.getProduct() == null) {
            System.out.println("        ✗ Product is null");
        } else {
            System.out.println("        ✓ Product exists");
            
            if (tradableProduct.getProduct().getContractualProduct() == null) {
                System.out.println("          ✗ Contractual product is null");
            } else {
                System.out.println("          ✓ Contractual product exists");
                
                if (tradableProduct.getProduct().getContractualProduct().getEconomicTerms() == null) {
                    System.out.println("            ✗ Economic terms are null");
                } else {
                    System.out.println("            ✓ Economic terms exist");
                }
            }
        }
    }

    private void validateParties(List<? extends Party> parties) {
        for (int i = 0; i < parties.size(); i++) {
            Party party = parties.get(i);
            System.out.println("        Party " + (i + 1) + ":");
            
            if (party.getPartyId() == null || party.getPartyId().isEmpty()) {
                System.out.println("          ⚠ No party IDs");
            } else {
                System.out.println("          ✓ Party IDs: " + party.getPartyId().size());
            }
            
            if (party.getName() == null) {
                System.out.println("          ⚠ No party name");
            } else {
                System.out.println("          ✓ Party name: " + party.getName().getValue());
            }
        }
    }

    private void validateReportableEvent(ReportableEvent reportableEvent) {
        System.out.println("    → Validating ReportableEvent structure:");
        
        if (reportableEvent.getOriginatingWorkflowStep() == null) {
            System.out.println("      ✗ CRITICAL: Originating workflow step is null");
        } else {
            System.out.println("      ✓ Originating workflow step exists");
            validateWorkflowStep(reportableEvent.getOriginatingWorkflowStep());
        }
    }

    private void validateWorkflowStep(WorkflowStep workflowStep) {
        System.out.println("    → Validating WorkflowStep structure:");
        
        if (workflowStep.getBusinessEvent() == null) {
            System.out.println("      ⚠ Business event is null (may be in proposed event)");
        } else {
            System.out.println("      ✓ Business event exists");
            validateBusinessEvent(workflowStep.getBusinessEvent());
        }
        
        if (workflowStep.getProposedEvent() != null) {
            System.out.println("      ✓ Proposed event exists");
        }
    }

    private void validateBusinessEvent(BusinessEvent businessEvent) {
        System.out.println("      → Validating BusinessEvent structure:");
        
        if (businessEvent.getAfter() == null || businessEvent.getAfter().isEmpty()) {
            System.out.println("        ⚠ No 'after' trade states");
        } else {
            System.out.println("        ✓ After trade states: " + businessEvent.getAfter().size());
        }
        
        if (businessEvent.getInstruction() == null || businessEvent.getInstruction().isEmpty()) {
            System.out.println("        ⚠ No instructions (before states)");
        } else {
            System.out.println("        ✓ Instructions: " + businessEvent.getInstruction().size());
            // Check for before states in instructions
            long beforeStatesCount = businessEvent.getInstruction().stream()
                    .filter(instruction -> instruction.getBefore() != null)
                    .count();
            if (beforeStatesCount > 0) {
                System.out.println("        ✓ Before trade states found in instructions: " + beforeStatesCount);
            } else {
                System.out.println("        ⚠ No before trade states in instructions");
            }
        }
    }

    private void validateForDRR(String filePath, String detectedType) {
        System.out.println("\n--- DRR-Specific Validation ---");
        
        Trade trade = extractTradeForDRR(filePath, detectedType);
        
        if (trade == null) {
            System.out.println("✗ CRITICAL: Cannot extract Trade object for DRR processing");
            System.out.println("  → DRR requires a valid Trade object");
            provideSuggestions(detectedType);
        } else {
            System.out.println("✓ Trade object can be extracted for DRR");
            validateDRRRequirements(trade);
        }
    }

    private Trade extractTradeForDRR(String filePath, String detectedType) {
        try {
            switch (detectedType) {
                case "TradeState":
                    TradeState ts = ResourcesUtils.getObjectAndResolveReferences(TradeState.class, filePath);
                    return ts.getTrade();
                    
                case "Trade":
                    return ResourcesUtils.getObjectAndResolveReferences(Trade.class, filePath);
                    
                default:
                    return null;
            }
        } catch (Exception e) {
            System.out.println("  → Failed to extract trade: " + e.getMessage());
            return null;
        }
    }

    private void validateDRRRequirements(Trade trade) {
        System.out.println("  → Checking DRR-specific requirements:");
        
        boolean hasUTI = trade.getTradeIdentifier().stream()
                .anyMatch(id -> id.getIdentifierType() == TradeIdentifierTypeEnum.UNIQUE_TRANSACTION_IDENTIFIER);
        
        if (!hasUTI) {
            System.out.println("    ⚠ No UTI (Unique Transaction Identifier) found");
        } else {
            System.out.println("    ✓ UTI found");
        }
        
        if (trade.getTradeDate() == null) {
            System.out.println("    ⚠ Trade date missing - may affect event timestamp generation");
        }
        
        if (trade.getParty() == null || trade.getParty().size() < 2) {
            System.out.println("    ⚠ Less than 2 parties - may affect reporting party identification");
        }
        
        System.out.println("    ✓ Basic DRR requirements check completed");
    }

    private void provideSuggestions(String detectedType) {
        System.out.println("\n--- Suggestions for Fixing Issues ---");
        
        switch (detectedType) {
            case "TradeState":
                System.out.println("• Your file is a TradeState but Trade is null");
                System.out.println("• Ensure the JSON has a 'trade' field with proper structure");
                System.out.println("• Example structure: { \"trade\": { \"tradeIdentifier\": [...], ... }, \"state\": {...} }");
                break;
                
            case "ReportableEvent":
                System.out.println("• Your file is a ReportableEvent - you need to extract the TradeState");
                System.out.println("• Use ReportableEvent.class to load, then navigate to trade data");
                System.out.println("• Path: reportableEvent.getOriginatingWorkflowStep().getBusinessEvent()...");
                break;
                
            case "WorkflowStep":
                System.out.println("• Your file is a WorkflowStep - extract TradeState from business event");
                System.out.println("• Use WorkflowStep.class to load");
                System.out.println("• Navigate through businessEvent.getAfter() or getInstruction() for before states");
                break;
                
            case "Unknown":
                System.out.println("• File structure doesn't match standard CDM patterns");
                System.out.println("• Check if it's a valid CDM JSON file");
                System.out.println("• Verify the JSON was generated correctly from FpML or other sources");
                break;
                
            default:
                System.out.println("• Consider using a different CDM type for loading");
                System.out.println("• Check the CDM documentation for proper structure");
        }
        
        System.out.println("\nGeneral recommendations:");
        System.out.println("• Use files from result-json-files/fpml-5-10/record-keeping/products/ for TradeState");
        System.out.println("• Use files from result-json-files/fpml-5-10/record-keeping/events/ for ReportableEvent");
        System.out.println("• Ensure reference resolution works by checking for proper globalReference/externalReference usage");
    }
} 
