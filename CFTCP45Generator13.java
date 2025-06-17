package com.regnosys.drr.examples;

import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.event.common.BusinessEvent;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.workflow.WorkflowStep;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;
import com.regnosys.drr.examples.util.ResourcesUtils;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import drr.enrichment.common.trade.functions.Create_ReportableEvents;
import drr.regulation.common.MandatorilyClearableEnum;
import drr.regulation.common.RegimeNameEnum;
import drr.regulation.common.ReportableEvent;
import drr.regulation.common.ReportableInformation;
import drr.regulation.common.ReportingRegime;
import drr.regulation.common.SupervisoryBodyEnum;
import drr.regulation.common.ReportingRoleEnum;
import drr.regulation.common.ReportingSide;
import drr.regulation.common.metafields.FieldWithMetaRegimeNameEnum;
import com.rosetta.model.metafields.FieldWithMetaString;
import drr.regulation.common.metafields.FieldWithMetaSupervisoryBodyEnum;
import com.rosetta.model.metafields.MetaFields;
import drr.enrichment.common.trade.functions.Create_TransactionReportInstruction;
import drr.regulation.common.functions.ExtractTradeCounterparty;
import drr.regulation.cftc.rewrite.CFTCPart45TransactionReport;
import drr.regulation.cftc.rewrite.reports.CFTCPart45ReportFunction;
import drr.regulation.common.TransactionReportInstruction;
import drr.regulation.common.ExecutionVenueTypeEnum;
import drr.regulation.common.ConfirmationMethodEnum;
import drr.regulation.common.PartyInformation;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.base.staticdata.party.PartyIdentifierTypeEnum;
import cdm.base.staticdata.party.Counterparty;

import java.io.IOException;
import java.util.List;

public class CFTCP45Generator {

    private final Injector injector;
    private final Create_ReportableEvents createReportableEvents;

    CFTCP45Generator() {
        this.injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
        this.createReportableEvents = injector.getInstance(Create_ReportableEvents.class);
    }

    public static void main(String[] args) throws IOException {
        // 1. Load and validate the CDM object - CORRECTED to load as ReportableEvent
        // The JSON has "originatingWorkflowStep" as top-level, so it's a ReportableEvent structure
        System.out.println("=== Loading CDM Object ===");
        
        try {
            // First try loading as ReportableEvent (likely correct)
            ReportableEvent reportableEvent = ResourcesUtils.getObjectAndResolveReferences(ReportableEvent.class, "regulatory-reporting/input/events/New-Trade-01.json");
            System.out.println("[SUCCESS] Loaded as ReportableEvent");
            
            // 2. Analyze the CDM object structure
            CFTCP45Generator generator = new CFTCP45Generator();
            generator.analyzeCDMObject(reportableEvent);
            
            // 3. Run comprehensive product validation
            System.out.println("\n=== COMPREHENSIVE PRODUCT VALIDATION ===");
            generator.validateAllProductPaths(reportableEvent);
            System.out.println("==========================================");
            
            // 4. Try to generate the DRR report
            System.out.println("\n=== Generating DRR Report ===");
            try {
                generator.runReport(reportableEvent);
            } catch (IOException e) {
                System.out.println("Error running report: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("[ERROR] Could not load as ReportableEvent: " + e.getMessage());
            
            // Fallback: try loading as WorkflowStep
            try {
                WorkflowStep workflowStep = ResourcesUtils.getObjectAndResolveReferences(WorkflowStep.class, "regulatory-reporting/input/events/New-Trade-01.json");
                System.out.println("[SUCCESS] Loaded as WorkflowStep");
                
                // 2. Analyze the CDM object structure
                CFTCP45Generator generator = new CFTCP45Generator();
                generator.analyzeCDMObject(workflowStep);
                
                // 3. Validate fields for DRR
                System.out.println("\n=== DRR Field Validation ===");
                generator.validateDRRFields(workflowStep);
                
                // 4. Create ReportableEvent
                System.out.println("\n=== Creating ReportableEvent ===");
                generator.createReportableEvent(workflowStep);
                
            } catch (Exception e2) {
                System.out.println("[ERROR] Could not load as WorkflowStep either: " + e2.getMessage());
                
                // Last resort: try to understand the JSON structure
                try {
                    String json = com.google.common.io.Resources.toString(
                        com.google.common.io.Resources.getResource("regulatory-reporting/input/events/New-Trade-01.json"), 
                        java.nio.charset.StandardCharsets.UTF_8);
                    System.out.println("\nJSON structure (first 1000 chars):");
                    System.out.println(json.substring(0, Math.min(1000, json.length())));
                } catch (Exception e3) {
                    System.out.println("[ERROR] Could not even read the JSON file: " + e3.getMessage());
                }
            }
        }
    }

    private void createReportableEvent(WorkflowStep workflowStep) {
        System.out.println("Converting WorkflowStep to ReportableEvent...");
        List<? extends ReportableEvent> reportableEvents = createReportableEvents.evaluate(workflowStep);
        
        System.out.println("\nReportableEvent Creation Results:");
        System.out.println("Number of ReportableEvents created: " + reportableEvents.size());
        
        for (int i = 0; i < reportableEvents.size(); i++) {
            ReportableEvent event = reportableEvents.get(i);
            System.out.println("\nReportableEvent " + (i + 1) + ":");
            System.out.println("- Originating WorkflowStep: " + (event.getOriginatingWorkflowStep() != null ? "Present" : "Missing"));
            System.out.println("- Reportable Information: " + (event.getReportableInformation() != null ? "Present" : "Missing"));
            
            // DEBUG: Inspect ReportableEvent structure
            System.out.println("\n=== DEBUGGING ReportableEvent Structure ===");
            if (event.getReportableTrade() != null) {
                System.out.println("[OK] ReportableTrade exists");
                if (event.getReportableTrade().getTrade() != null && event.getReportableTrade().getTrade().getParty() != null) {
                    System.out.println("[OK] Party list exists, size: " + event.getReportableTrade().getTrade().getParty().size());
                    if (!event.getReportableTrade().getTrade().getParty().isEmpty()) {
                        System.out.println("[OK] Party list has entries - should extract from here!");
                    } else {
                        System.out.println("[ERROR] Party list is EMPTY");
                    }
                } else {
                    System.out.println("[ERROR] Party list is NULL");
                }
            } else {
                System.out.println("[ERROR] ReportableTrade is NULL");
            }
            
            if (event.getReportableInformation() != null) {
                System.out.println("[OK] ReportableInformation exists (for regulatory metadata)");
                if (event.getReportableInformation().getPartyInformation() != null) {
                    System.out.println("[OK] PartyInformation list exists, size: " + event.getReportableInformation().getPartyInformation().size());
                    if (!event.getReportableInformation().getPartyInformation().isEmpty()) {
                        System.out.println("[OK] PartyInformation has entries");
                    } else {
                        System.out.println("[ERROR] PartyInformation list is EMPTY");
                    }
                } else {
                    System.out.println("[ERROR] PartyInformation list is NULL");
                }
            } else {
                System.out.println("[ERROR] ReportableInformation is NULL");
            }
            
            // DEBUG: Check if we need to go back to original trade instead
            if (event.getOriginatingWorkflowStep() != null) {
                System.out.println("[OK] OriginatingWorkflowStep exists - can fallback to original trade data");
                if (event.getOriginatingWorkflowStep().getBusinessEvent() != null &&
                    !event.getOriginatingWorkflowStep().getBusinessEvent().getAfter().isEmpty()) {
                    TradeState tradeState = event.getOriginatingWorkflowStep().getBusinessEvent().getAfter().get(0);
                    if (tradeState.getTrade() != null && tradeState.getTrade().getParty() != null) {
                        System.out.println("[OK] Original trade has " + tradeState.getTrade().getParty().size() + " parties");
                    }
                }
            }
            System.out.println("===============================================");
            
            // NEW: Comprehensive Product Validation
            System.out.println("\n=== COMPREHENSIVE PRODUCT VALIDATION ===");
            validateAllProductPaths(event);
            System.out.println("=========================================");
            
            // DEBUG: Inspect Product Information from Multiple Sources
            System.out.println("\n=== DEBUGGING Product Information (All Extraction Paths) ===");
            if (event.getReportableTrade() != null && event.getReportableTrade().getTrade() != null) {
                Trade trade = event.getReportableTrade().getTrade();
                
                // Path 1: Traditional tradableProduct approach
                System.out.println("\n[PATH 1] Checking tradableProduct path:");
                if (trade.getTradableProduct() != null) {
                    System.out.println("[OK] TradableProduct exists");
                    
                    if (trade.getTradableProduct().getProduct() != null) {
                        System.out.println("[OK] Product exists via tradableProduct");
                        System.out.println("- Product type: " + trade.getTradableProduct().getProduct().getClass().getSimpleName());
                        
                        // Check for contractual product
                        if (trade.getTradableProduct().getProduct().getContractualProduct() != null) {
                            System.out.println("[OK] ContractualProduct exists via tradableProduct");
                            if (trade.getTradableProduct().getProduct().getContractualProduct().getEconomicTerms() != null) {
                                System.out.println("[OK] EconomicTerms exist via tradableProduct");
                            }
                        }
                        
                        // Try to print product structure
                        try {
                            System.out.println("\nProduct JSON Structure (tradableProduct path):");
                            System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(trade.getTradableProduct().getProduct()));
                        } catch (Exception e) {
                            System.out.println("Could not serialize product: " + e.getMessage());
                        }
                    } else {
                        System.out.println("[ERROR] Product is NULL via tradableProduct");
                    }
                    
                    // Check trade lots
                    if (trade.getTradableProduct().getTradeLot() != null && !trade.getTradableProduct().getTradeLot().isEmpty()) {
                        System.out.println("[OK] TradeLot exists, size: " + trade.getTradableProduct().getTradeLot().size());
                        
                        try {
                            System.out.println("\nTradeLot JSON Structure:");
                            System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(trade.getTradableProduct().getTradeLot()));
                        } catch (Exception e) {
                            System.out.println("Could not serialize tradeLot: " + e.getMessage());
                        }
                    } else {
                        System.out.println("[WARN] TradeLot is NULL or EMPTY");
                    }
                } else {
                    System.out.println("[ERROR] TradableProduct is NULL - trying alternative paths...");
                }
                
                // Path 2: Check if there's a Product elsewhere in the structure
                System.out.println("\n[PATH 2] Checking alternative product locations:");
                
                // Check if there's any product information in the trade JSON structure
                try {
                    String tradeJson = RosettaObjectMapper.getNewRosettaObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(trade);
                    
                    // Look for product-related keywords in the JSON
                    if (tradeJson.contains("contractualProduct")) {
                        System.out.println("[OK] Found 'contractualProduct' in trade structure");
                    }
                    if (tradeJson.contains("economicTerms")) {
                        System.out.println("[OK] Found 'economicTerms' in trade structure");
                    }
                    if (tradeJson.contains("productTaxonomy")) {
                        System.out.println("[OK] Found 'productTaxonomy' in trade structure");
                    }
                    if (tradeJson.contains("assetClass")) {
                        System.out.println("[OK] Found 'assetClass' in trade structure");
                    }
                    if (tradeJson.contains("payout")) {
                        System.out.println("[OK] Found 'payout' in trade structure");
                    }
                    
                    // Print sections of JSON that might contain product info
                    if (tradeJson.contains("product") && !tradeJson.contains("tradableProduct")) {
                        System.out.println("[INFO] Product information exists but not under tradableProduct");
                        // Find and extract the product section
                        String[] lines = tradeJson.split("\n");
                        boolean inProductSection = false;
                        int braceCount = 0;
                        System.out.println("\n[EXTRACTED] Product-related content found:");
                        for (String line : lines) {
                            if (line.contains("\"product\"") && line.contains(":")) {
                                inProductSection = true;
                                braceCount = 0;
                            }
                            if (inProductSection) {
                                System.out.println(line);
                                braceCount += line.chars().mapToObj(c -> (char) c).mapToInt(c -> c == '{' ? 1 : c == '}' ? -1 : 0).sum();
                                if (braceCount <= 0 && line.contains("}")) {
                                    break;
                                }
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    System.out.println("Could not analyze trade JSON: " + e.getMessage());
                }
                
                // Path 3: Check workflow step for product information
                System.out.println("\n[PATH 3] Checking WorkflowStep for product information:");
                if (event.getOriginatingWorkflowStep() != null && 
                    event.getOriginatingWorkflowStep().getBusinessEvent() != null &&
                    !event.getOriginatingWorkflowStep().getBusinessEvent().getAfter().isEmpty()) {
                    
                    TradeState originalTradeState = event.getOriginatingWorkflowStep().getBusinessEvent().getAfter().get(0);
                    if (originalTradeState.getTrade() != null && 
                        originalTradeState.getTrade().getTradableProduct() != null &&
                        originalTradeState.getTrade().getTradableProduct().getProduct() != null) {
                        System.out.println("[OK] Product found in original WorkflowStep");
                        System.out.println("- Product type: " + originalTradeState.getTrade().getTradableProduct().getProduct().getClass().getSimpleName());
                    } else {
                        System.out.println("[WARN] No product in original WorkflowStep either");
                    }
                }
                
                // Path 4: Raw inspection of the entire event structure for any product data
                System.out.println("\n[PATH 4] Raw search for any product-related data:");
                try {
                    String eventJson = RosettaObjectMapper.getNewRosettaObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(event);
                    
                    String[] productKeywords = {
                        "contractualProduct", "economicTerms", "productTaxonomy", 
                        "primaryAssetClass", "secondaryAssetClass", "productType",
                        "interestRatePayout", "commodityPayout", "optionPayout",
                        "forwardPayout", "performancePayout", "creditDefaultPayout"
                    };
                    
                    System.out.println("Product-related keywords found in event:");
                    for (String keyword : productKeywords) {
                        if (eventJson.contains(keyword)) {
                            System.out.println("  [FOUND] " + keyword);
                        }
                    }
                    
                } catch (Exception e) {
                    System.out.println("Could not analyze event JSON: " + e.getMessage());
                }
                
                // Check trade identifiers for additional info
                if (trade.getTradeIdentifier() != null && !trade.getTradeIdentifier().isEmpty()) {
                    System.out.println("\n[OK] TradeIdentifier exists, size: " + trade.getTradeIdentifier().size());
                    trade.getTradeIdentifier().forEach(identifier -> {
                        System.out.println("- Identifier Type: " + identifier.getIdentifierType());
                        if (identifier.getAssignedIdentifier() != null && !identifier.getAssignedIdentifier().isEmpty()) {
                            identifier.getAssignedIdentifier().forEach(assignedId -> {
                                if (assignedId.getIdentifier() != null) {
                                    System.out.println("  - Value: " + assignedId.getIdentifier().getValue());
                                }
                            });
                        }
                    });
                } else {
                    System.out.println("[ERROR] TradeIdentifier is NULL or EMPTY");
                }
                
                // Check trade date
                if (trade.getTradeDate() != null) {
                    System.out.println("[OK] TradeDate exists: " + trade.getTradeDate().getValue());
                } else {
                    System.out.println("[ERROR] TradeDate is NULL");
                }
            } else {
                System.out.println("[ERROR] Cannot access Trade for product information");
            }
            System.out.println("========================================");
            
            // Print the created reportable event
            try {
                System.out.println("\nCreated Reportable Event:");
                System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(event));
            } catch (IOException e) {
                System.out.println("Error printing reportable event: " + e.getMessage());
            }
            
            // Extract real party data from the ReportableEvent's originating trade
            System.out.println("\n=== Extracting Real Party Data ===");
            PartyData partyData = extractRealPartyData(event);
            
            // Create ReportableInformation with real party details
            ReportableInformation.ReportableInformationBuilder reportableInfoBuilder = ReportableInformation.builder()
                .setExecutionVenueType(ExecutionVenueTypeEnum.OFF_FACILITY)
                .setConfirmationMethod(ConfirmationMethodEnum.NOT_CONFIRMED)
                .setIntragroup(false);

            // Create PartyInformation using real data
            PartyInformation.PartyInformationBuilder party1InfoBuilder = createPartyInformationFromReal(
                CounterpartyRoleEnum.PARTY_1, 
                partyData.party1Lei, 
                partyData.party1Name,
                ReportingRoleEnum.REPORTING_PARTY
            );

            PartyInformation.PartyInformationBuilder party2InfoBuilder = createPartyInformationFromReal(
                CounterpartyRoleEnum.PARTY_2,
                partyData.party2Lei,
                partyData.party2Name,
                ReportingRoleEnum.VOLUNTARY_PARTY
            );

            // Add party information to reportable information
            reportableInfoBuilder.addPartyInformation(party1InfoBuilder.build());
            reportableInfoBuilder.addPartyInformation(party2InfoBuilder.build());

            // Update the event with the new reportable information
            event = event.toBuilder()
                .setReportableInformation(reportableInfoBuilder.build())
                .build();

            // Print the populated reportable information
            System.out.println("\nPopulated Reportable Information with Real Data:");
            System.out.println("- Execution Venue Type: " + event.getReportableInformation().getExecutionVenueType());
            System.out.println("- Confirmation Method: " + event.getReportableInformation().getConfirmationMethod());
            System.out.println("- Intragroup: " + event.getReportableInformation().getIntragroup());
            System.out.println("\nReal Party Information:");
            System.out.println("  Party 1 (ANZ Bank):");
            System.out.println("  - LEI: " + partyData.party1Lei);
            System.out.println("  - Name: " + partyData.party1Name);
            System.out.println("  Party 2 (Merrill Lynch):");
            System.out.println("  - LEI: " + partyData.party2Lei);
            System.out.println("  - Name: " + partyData.party2Name);

            // Print the final reportable event after updates
            try {
                System.out.println("\nFinal Reportable Event After Updates:");
                System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(event));
            } catch (IOException e) {
                System.out.println("Error printing final reportable event: " + e.getMessage());
            }

            try {
                runReport(event);
            } catch (IOException e) {
                System.out.println("Error running report: " + e.getMessage());
            }
        }
    }

    // Comprehensive Product Validation Method - CORRECT CDM APPROACH
    private void validateAllProductPaths(ReportableEvent reportableEvent) {
        System.out.println("Starting comprehensive product validation using CORRECT CDM methods...");
        
        // Path 1: Use CDM ProductForEvent Function Pattern  
        System.out.println("\n[VALIDATION PATH 1] CDM ProductForEvent Pattern (Recommended)");
        try {
            if (reportableEvent.getReportableTrade() != null && 
                reportableEvent.getReportableTrade().getTrade() != null) {
                
                Trade trade = reportableEvent.getReportableTrade().getTrade();
                System.out.println("  ✓ Trade extracted from ReportableEvent");
                
                // Test the standard CDM path: trade -> tradableProduct -> product
                if (trade.getTradableProduct() != null) {
                    System.out.println("  ✓ TradableProduct exists");
                    
                    if (trade.getTradableProduct().getProduct() != null) {
                        System.out.println("  🎉 FOUND PRODUCT via trade.getTradableProduct().getProduct()!");
                        System.out.println("  - Product Class: " + trade.getTradableProduct().getProduct().getClass().getSimpleName());
                        
                        // Analyze this product in detail
                        analyzeProductInDetail(trade.getTradableProduct().getProduct(), "trade.getTradableProduct().getProduct()");
                        
                    } else {
                        System.out.println("  ❌ trade.getTradableProduct().getProduct() returns NULL");
                    }
                } else {
                    System.out.println("  ❌ trade.getTradableProduct() returns NULL");
                }
            } else {
                System.out.println("  ❌ Cannot access Trade from ReportableEvent");
            }
        } catch (Exception e) {
            System.out.println("  ❌ Error in CDM ProductForEvent pattern: " + e.getMessage());
        }
        
        // Path 2: Check OriginatingWorkflowStep → BusinessEvent → TradeState → Trade → TradableProduct → Product
        System.out.println("\n[VALIDATION PATH 2] OriginatingWorkflowStep → TradeState → Trade → Product");
        try {
            if (reportableEvent.getOriginatingWorkflowStep() != null) {
                System.out.println("  ✓ OriginatingWorkflowStep exists");
                
                if (reportableEvent.getOriginatingWorkflowStep().getBusinessEvent() != null) {
                    System.out.println("  ✓ BusinessEvent exists");
                    
                    if (!reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getAfter().isEmpty()) {
                        System.out.println("  ✓ After states exist: " + reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getAfter().size());
                        
                        TradeState tradeState = reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getAfter().get(0);
                        System.out.println("  ✓ TradeState retrieved");
                        
                        if (tradeState.getTrade() != null) {
                            System.out.println("  ✓ Trade exists in TradeState");
                            Trade trade = tradeState.getTrade();
                            
                            // Test the CDM path from TradeState
                            if (trade.getTradableProduct() != null) {
                                System.out.println("  ✓ TradableProduct exists in TradeState.Trade");
                                
                                if (trade.getTradableProduct().getProduct() != null) {
                                    System.out.println("  🎉 FOUND PRODUCT via TradeState.Trade.getTradableProduct().getProduct()!");
                                    System.out.println("  - Product Class: " + trade.getTradableProduct().getProduct().getClass().getSimpleName());
                                    
                                    // Analyze this product in detail
                                    analyzeProductInDetail(trade.getTradableProduct().getProduct(), "TradeState.Trade.getTradableProduct().getProduct()");
                                    
                                } else {
                                    System.out.println("  ❌ TradeState.Trade.getTradableProduct().getProduct() returns NULL");
                                }
                            } else {
                                System.out.println("  ❌ TradeState.Trade.getTradableProduct() returns NULL");
                            }
                        } else {
                            System.out.println("  ❌ Trade is NULL in TradeState");
                        }
                    } else {
                        System.out.println("  ❌ No After states found");
                    }
                } else {
                    System.out.println("  ❌ BusinessEvent is NULL");
                }
            } else {
                System.out.println("  ❌ OriginatingWorkflowStep is NULL");
            }
        } catch (Exception e) {
            System.out.println("  ❌ Error in TradeState path: " + e.getMessage());
        }
        
        // Path 3: Use reflection to find ALL product-related methods (Discovery Mode)
        System.out.println("\n[VALIDATION PATH 3] CDM Trade Class Method Discovery");
        try {
            if (reportableEvent.getReportableTrade() != null && reportableEvent.getReportableTrade().getTrade() != null) {
                Trade trade = reportableEvent.getReportableTrade().getTrade();
                
                System.out.println("  Scanning Trade class for ALL available methods:");
                java.lang.reflect.Method[] methods = trade.getClass().getMethods();
                
                // List all getter methods to see what's available
                System.out.println("  ");
                System.out.println("  === ALL TRADE METHODS === ");
                for (java.lang.reflect.Method method : methods) {
                    if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                        System.out.println("    → " + method.getName() + "() → " + method.getReturnType().getSimpleName());
                    }
                }
                System.out.println("  ========================");
                
                // Now specifically test important CDM methods
                System.out.println("\n  Testing key CDM methods:");
                
                // Test getTradableProduct()
                try {
                    Object tradableProduct = trade.getTradableProduct();
                    if (tradableProduct != null) {
                        System.out.println("    ✓ getTradableProduct() → " + tradableProduct.getClass().getSimpleName());
                        
                        // Test getProduct() on TradableProduct
                        java.lang.reflect.Method getProductMethod = tradableProduct.getClass().getMethod("getProduct");
                        Object product = getProductMethod.invoke(tradableProduct);
                        if (product != null) {
                            System.out.println("    🎉 getTradableProduct().getProduct() → " + product.getClass().getSimpleName());
                            analyzeProductInDetail(product, "getTradableProduct().getProduct()");
                        } else {
                            System.out.println("    ❌ getTradableProduct().getProduct() returns NULL");
                        }
                    } else {
                        System.out.println("    ❌ getTradableProduct() returns NULL");
                    }
                } catch (Exception e) {
                    System.out.println("    ❌ Error testing getTradableProduct(): " + e.getMessage());
                }
                
                // Test other potential product-related methods
                String[] testMethods = {"getTradeIdentifier", "getTradeDate", "getParty", "getExecution", "getContractDetails"};
                for (String methodName : testMethods) {
                    try {
                        java.lang.reflect.Method method = trade.getClass().getMethod(methodName);
                        Object result = method.invoke(trade);
                        if (result != null) {
                            System.out.println("    ✓ " + methodName + "() → " + result.getClass().getSimpleName());
                        } else {
                            System.out.println("    ❌ " + methodName + "() returns NULL");
                        }
                    } catch (NoSuchMethodException e) {
                        System.out.println("    ❌ " + methodName + "() method does not exist");
                    } catch (Exception e) {
                        System.out.println("    ❌ Error calling " + methodName + "(): " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  ❌ Error in method discovery: " + e.getMessage());
        }
        
        // Path 4: Extract EventType and other DRR fields
        System.out.println("\n[VALIDATION PATH 4] Extract EventType and DRR Fields");
        try {
            extractEventTypeFromReportableEvent(reportableEvent);
        } catch (Exception e) {
            System.out.println("  ❌ Error extracting EventType: " + e.getMessage());
        }
        
        System.out.println("\nProduct validation completed.");
    }
    
    // New method to extract EventType for DRR
    private void extractEventTypeFromReportableEvent(ReportableEvent reportableEvent) {
        System.out.println("  Extracting EventType for DRR fields...");
        
        try {
            // Check ReportableInformation for action type
            if (reportableEvent.getReportableInformation() != null) {
                System.out.println("    ✓ ReportableInformation exists");
                
                if (reportableEvent.getReportableInformation().getReportableAction() != null) {
                    System.out.println("    ✓ ReportableAction: " + reportableEvent.getReportableInformation().getReportableAction());
                }
            }
            
            // Check OriginatingWorkflowStep for action and intent
            if (reportableEvent.getOriginatingWorkflowStep() != null) {
                System.out.println("    ✓ OriginatingWorkflowStep exists");
                
                if (reportableEvent.getOriginatingWorkflowStep().getAction() != null) {
                    System.out.println("    ✓ WorkflowStep Action: " + reportableEvent.getOriginatingWorkflowStep().getAction());
                }
                
                if (reportableEvent.getOriginatingWorkflowStep().getBusinessEvent() != null) {
                    System.out.println("    ✓ BusinessEvent exists");
                    
                    if (reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getIntent() != null) {
                        System.out.println("    ✓ BusinessEvent Intent: " + reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getIntent());
                    }
                    
                    if (!reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getInstruction().isEmpty()) {
                        System.out.println("    ✓ Instructions exist: " + reportableEvent.getOriginatingWorkflowStep().getBusinessEvent().getInstruction().size());
                        // You can map these to DRR EventType:
                        // - Intent = CONTRACT_FORMATION → EventType = "TRAD"
                        // - Intent = CONTRACT_TERMINATION → EventType = "TERM"
                        // - Intent = CONTRACT_TERMS_AMENDMENT → EventType = "MODI"
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("    ❌ Error extracting EventType: " + e.getMessage());
        }
    }

    private void analyzeProductInDetail(Object product, String extractionPath) {
        System.out.println("\n    === DETAILED PRODUCT ANALYSIS ===");
        System.out.println("    Extraction Path: " + extractionPath);
        System.out.println("    Product Class: " + product.getClass().getName());
        
        try {
            // Serialize the product to see its full structure
            String productJson = RosettaObjectMapper.getNewRosettaObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(product);
            
            System.out.println("    Product JSON Structure:");
            System.out.println(productJson);
            
            // Look for specific CDM fields
            String[] importantFields = {
                "contractualProduct", "economicTerms", "productTaxonomy",
                "primaryAssetClass", "secondaryAssetClass", "productType",
                "payout", "interestRatePayout", "commodityPayout", "optionPayout",
                "notional", "currency", "assetClass", "contractType"
            };
            
            System.out.println("\n    Key Product Fields Found:");
            for (String field : importantFields) {
                if (productJson.contains(field)) {
                    System.out.println("      ✓ " + field);
                }
            }
            
        } catch (Exception e) {
            System.out.println("    ❌ Could not serialize product: " + e.getMessage());
            
            // Fallback: use reflection to examine fields
            System.out.println("    Fallback - Available methods:");
            java.lang.reflect.Method[] methods = product.getClass().getMethods();
            for (java.lang.reflect.Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object result = method.invoke(product);
                        if (result != null) {
                            System.out.println("      → " + method.getName() + "(): " + result.getClass().getSimpleName());
                        }
                    } catch (Exception ignored) {
                        // Skip methods that fail
                    }
                }
            }
        }
        System.out.println("    ================================");
    }

    // Helper class to hold extracted party data
    private static class PartyData {
        final String party1Lei;
        final String party1Name;
        final String party2Lei;
        final String party2Name;
        
        PartyData(String party1Lei, String party1Name, String party2Lei, String party2Name) {
            this.party1Lei = party1Lei;
            this.party1Name = party1Name;
            this.party2Lei = party2Lei;
            this.party2Name = party2Name;
        }
    }

    private PartyData extractRealPartyData(ReportableEvent reportableEvent) {
        // Dummy fallback values for easy identification when extraction fails
        String party1Lei = "DUMMY0000000000LEI01";  // Fallback - Party 1
        String party1Name = "DUMMY_PARTY_1_NAME";
        String party2Lei = "DUMMY0000000000LEI02";  // Fallback - Party 2
        String party2Name = "DUMMY_PARTY_2_NAME";
        
        try {
            // Extract directly from ReportableEvent's already-processed party information
            if (reportableEvent.getReportableTrade() != null && 
                reportableEvent.getReportableTrade().getTrade() != null &&
                reportableEvent.getReportableTrade().getTrade().getParty() != null &&
                !reportableEvent.getReportableTrade().getTrade().getParty().isEmpty()) {
                
                System.out.println("Extracting party data from ReportableEvent.getReportableTrade()...");
                
                List<? extends Party> partyList = reportableEvent.getReportableTrade().getTrade().getParty();
                
                // Extract Party 1 from ReportableEvent
                if (!partyList.isEmpty()) {
                    Party party1 = partyList.get(0);
                    
                    // Extract LEI
                    if (party1.getPartyId() != null && !party1.getPartyId().isEmpty()) {
                        PartyIdentifier partyId1 = party1.getPartyId().get(0);
                        if (partyId1.getIdentifier() != null && partyId1.getIdentifier().getValue() != null) {
                            party1Lei = partyId1.getIdentifier().getValue();
                            System.out.println("Extracted Party 1 LEI from ReportableEvent: " + party1Lei);
                        }
                    }
                    
                    // Extract Name
                    if (party1.getName() != null && party1.getName().getValue() != null) {
                        party1Name = party1.getName().getValue();
                        System.out.println("Extracted Party 1 Name from ReportableEvent: " + party1Name);
                    }
                }
                
                // Extract Party 2 from ReportableEvent
                if (partyList.size() >= 2) {
                    Party party2 = partyList.get(1);
                    
                    // Extract LEI
                    if (party2.getPartyId() != null && !party2.getPartyId().isEmpty()) {
                        PartyIdentifier partyId2 = party2.getPartyId().get(0);
                        if (partyId2.getIdentifier() != null && partyId2.getIdentifier().getValue() != null) {
                            party2Lei = partyId2.getIdentifier().getValue();
                            System.out.println("Extracted Party 2 LEI from ReportableEvent: " + party2Lei);
                        }
                    }
                    
                    // Extract Name
                    if (party2.getName() != null && party2.getName().getValue() != null) {
                        party2Name = party2.getName().getValue();
                        System.out.println("Extracted Party 2 Name from ReportableEvent: " + party2Name);
                    }
                }
                
                System.out.println("Successfully extracted party data from ReportableEvent!");
                
            } else {
                System.out.println("No party information found in ReportableEvent.getReportableTrade(), using fallback values");
            }
            
        } catch (Exception e) {
            System.out.println("Warning: Could not extract party data from ReportableEvent, using default values: " + e.getMessage());
        }
        
        return new PartyData(party1Lei, party1Name, party2Lei, party2Name);
    }

    private PartyInformation.PartyInformationBuilder createPartyInformationFromReal(
            CounterpartyRoleEnum role,
            String lei,
            String name,
            ReportingRoleEnum reportingRole) {
        
        System.out.println("Creating party information for " + role + " with LEI: " + lei + " and name: " + name);
        
        Party party = Party.builder()
            .addPartyId(PartyIdentifier.builder()
                .setIdentifier(FieldWithMetaString.builder()
                    .setValue(lei)
                    .setMeta(MetaFields.builder()
                        .setScheme("http://www.fpml.org/coding-scheme/external/iso17442")
                        .build())
                    .build())
                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                .build())
            .setName(FieldWithMetaString.builder()
                .setValue(name)
                .build())
            .build();

        ReportingRegime.ReportingRegimeBuilder regimeBuilder = ReportingRegime.builder()
            .setRegimeName(FieldWithMetaRegimeNameEnum.builder()
                .setValue(RegimeNameEnum.DODD_FRANK_ACT)
                .setMeta(MetaFields.builder()
                    .setScheme("http://www.fpml.org/coding-scheme/external/regime-name")
                    .build())
                .build())
            .setSupervisoryBody(FieldWithMetaSupervisoryBodyEnum.builder()
                .setValue(SupervisoryBodyEnum.CFTC)
                .setMeta(MetaFields.builder()
                    .setScheme("http://www.fpml.org/coding-scheme/external/supervisory-body")
                    .build())
                .build())
            .setReportingRole(reportingRole)
            .setMandatorilyClearable(MandatorilyClearableEnum.PRODUCT_MANDATORY_BUT_NOT_CPTY);

        return PartyInformation.builder()
            .setPartyReference(ReferenceWithMetaParty.builder().setValue(party).build())
            .addRegimeInformation(regimeBuilder.build());
    }

    private PartyInformation.PartyInformationBuilder createPartyInformation(
            CounterpartyRoleEnum role,
            String lei,
            ReportingRoleEnum reportingRole) {
        
        Party party = Party.builder()
            .addPartyId(PartyIdentifier.builder()
                .setIdentifier(FieldWithMetaString.builder()
                    .setValue(lei)
                    .setMeta(MetaFields.builder()
                        .setScheme("http://www.fpml.org/coding-scheme/external/iso17442")
                        .build())
                    .build())
                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                .build())
            .build();

        ReportingRegime.ReportingRegimeBuilder regimeBuilder = ReportingRegime.builder()
            .setRegimeName(FieldWithMetaRegimeNameEnum.builder()
                .setValue(RegimeNameEnum.DODD_FRANK_ACT)
                .setMeta(MetaFields.builder()
                    .setScheme("http://www.fpml.org/coding-scheme/external/regime-name")
                    .build())
                .build())
            .setSupervisoryBody(FieldWithMetaSupervisoryBodyEnum.builder()
                .setValue(SupervisoryBodyEnum.CFTC)
                .setMeta(MetaFields.builder()
                    .setScheme("http://www.fpml.org/coding-scheme/external/supervisory-body")
                    .build())
                .build())
            .setReportingRole(reportingRole)
            .setMandatorilyClearable(MandatorilyClearableEnum.PRODUCT_MANDATORY_BUT_NOT_CPTY);

        return PartyInformation.builder()
            .setPartyReference(ReferenceWithMetaParty.builder().setValue(party).build())
            .addRegimeInformation(regimeBuilder.build());
    }

    private void validateDRRFields(WorkflowStep workflowStep) {
        System.out.println("\n=== CDM Object Field Validation ===");
        
        // Check WorkflowStep fields
        System.out.println("\nWorkflowStep Fields:");
        System.out.println("- BusinessEvent: " + (workflowStep.getBusinessEvent() != null ? "Present" : "Missing"));
        System.out.println("- ProposedEvent: " + (workflowStep.getProposedEvent() != null ? "Present" : "Missing"));
        
        if (workflowStep.getBusinessEvent() != null && !workflowStep.getBusinessEvent().getAfter().isEmpty()) {
            TradeState tradeState = workflowStep.getBusinessEvent().getAfter().get(0);
            
            // Check TradeState fields
            System.out.println("\nTradeState Fields:");
            System.out.println("- Trade: " + (tradeState.getTrade() != null ? "Present" : "Missing"));
            System.out.println("- State: " + (tradeState.getState() != null ? "Present" : "Missing"));
            
            if (tradeState.getTrade() != null) {
                // Check Trade fields
                System.out.println("\nTrade Fields:");
                System.out.println("- Trade Identifiers: " + (tradeState.getTrade().getTradeIdentifier() != null ? 
                    tradeState.getTrade().getTradeIdentifier().size() + " found" : "Missing"));
                validateTradeIdentifiers(tradeState.getTrade());
                System.out.println("- Parties: " + (tradeState.getTrade().getParty() != null ? 
                    tradeState.getTrade().getParty().size() + " found" : "Missing"));
                System.out.println("- Trade Date: " + (tradeState.getTrade().getTradeDate() != null ? "Present" : "Missing"));
                System.out.println("- Tradable Product: " + (tradeState.getTrade().getTradableProduct() != null ? "Present" : "Missing"));
            }
        }
    }

    private void validateTradeIdentifiers(Trade trade) {
        if (trade.getTradeIdentifier() != null && !trade.getTradeIdentifier().isEmpty()) {
            System.out.println("\nTrade Identifier Details:");
            trade.getTradeIdentifier().forEach(identifier -> {
                System.out.println("\nIdentifier:");
                System.out.println("  - Type: " + identifier.getIdentifierType());
                if (identifier.getAssignedIdentifier() != null && !identifier.getAssignedIdentifier().isEmpty()) {
                    identifier.getAssignedIdentifier().forEach(assignedId -> {
                        if (assignedId.getIdentifier() != null) {
                            System.out.println("  - Value: " + assignedId.getIdentifier().getValue());
                            if (assignedId.getIdentifier().getMeta() != null && 
                                assignedId.getIdentifier().getMeta().getScheme() != null) {
                                System.out.println("  - Scheme: " + assignedId.getIdentifier().getMeta().getScheme());
                            }
                        }
                    });
                } else {
                    System.out.println("  - [WARN] No assigned identifier value found");
                }
            });
        } else {
            System.out.println("[WARN] No trade identifiers found");
        }
    }

    private void analyzeCDMObject(Object cdmObject) {
        System.out.println("\n=== CDM Object Analysis ===");
        
        // Check if it's a WorkflowStep
        if (cdmObject instanceof WorkflowStep) {
            WorkflowStep workflowStep = (WorkflowStep) cdmObject;
            System.out.println("[OK] Object is a WorkflowStep");
            
            // CORRECTED: The JSON structure has "originatingWorkflowStep" containing the WorkflowStep
            // But we're loading the top-level object which IS the OriginatingWorkflowStep
            // Let's check what we actually have
            
            System.out.println("\nChecking WorkflowStep structure:");
            try {
                // Print the JSON structure to see what we have
                String workflowStepJson = RosettaObjectMapper.getNewRosettaObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(workflowStep);
                   
                // Check first 500 characters to see structure
                System.out.println("WorkflowStep JSON (first 500 chars):");
                System.out.println(workflowStepJson.substring(0, Math.min(500, workflowStepJson.length())));
                System.out.println("... (truncated)");
                
                // Check for specific fields
                if (workflowStepJson.contains("originatingWorkflowStep")) {
                    System.out.println("[INFO] Contains 'originatingWorkflowStep' field");
                }
                if (workflowStepJson.contains("businessEvent")) {
                    System.out.println("[INFO] Contains 'businessEvent' field");
                }
                if (workflowStepJson.contains("tradableProduct")) {
                    System.out.println("[INFO] Contains 'tradableProduct' field");
                }
                if (workflowStepJson.contains("contractualProduct")) {
                    System.out.println("[INFO] Contains 'contractualProduct' field");
                }
                
            } catch (Exception e) {
                System.out.println("[ERROR] Could not serialize WorkflowStep: " + e.getMessage());
            }
            
            analyzeWorkflowStep(workflowStep);
        }
        // Check if it's a BusinessEvent
        else if (cdmObject instanceof BusinessEvent) {
            BusinessEvent businessEvent = (BusinessEvent) cdmObject;
            System.out.println("[OK] Object is a BusinessEvent");
            analyzeBusinessEvent(businessEvent);
        }
        // Check if it's a ReportableEvent
        else if (cdmObject instanceof ReportableEvent) {
            ReportableEvent reportableEvent = (ReportableEvent) cdmObject;
            System.out.println("[OK] Object is a ReportableEvent");
            analyzeReportableEvent(reportableEvent);
        }
        // Check if it's a TradeState
        else if (cdmObject instanceof TradeState) {
            TradeState tradeState = (TradeState) cdmObject;
            System.out.println("[OK] Object is a TradeState");
            analyzeTradeState(tradeState);
        }
        else {
            System.out.println("[WARN] Unknown CDM object type: " + cdmObject.getClass().getName());
            
            // Use reflection to see available methods
            System.out.println("Available methods:");
            java.lang.reflect.Method[] methods = cdmObject.getClass().getMethods();
            for (java.lang.reflect.Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object result = method.invoke(cdmObject);
                        if (result != null) {
                            System.out.println("  → " + method.getName() + "() → " + result.getClass().getSimpleName());
                        }
                    } catch (Exception ignored) {
                        // Skip methods that fail
                    }
                }
            }
        }
        
        System.out.println("=========================");
    }

    private void analyzeWorkflowStep(WorkflowStep workflowStep) {
        System.out.println("\nWorkflowStep Analysis:");
        
        // Check BusinessEvent
        if (workflowStep.getBusinessEvent() != null) {
            System.out.println("[OK] Contains BusinessEvent");
            analyzeBusinessEvent(workflowStep.getBusinessEvent());
        } else {
            System.out.println("[WARN] No BusinessEvent found");
        }
        
        // Check ProposedEvent
        if (workflowStep.getProposedEvent() != null) {
            System.out.println("[OK] Contains ProposedEvent");
        } else {
            System.out.println("[WARN] No ProposedEvent found");
        }
    }

    private void analyzeBusinessEvent(BusinessEvent businessEvent) {
        System.out.println("\nBusinessEvent Analysis:");
        
        // Check After states
        if (businessEvent.getAfter() != null && !businessEvent.getAfter().isEmpty()) {
            System.out.println("[OK] Contains " + businessEvent.getAfter().size() + " After states");
            for (TradeState state : businessEvent.getAfter()) {
                analyzeTradeState(state);
            }
        } else {
            System.out.println("[WARN] No After states found");
        }
        
        // Check Instructions
        if (businessEvent.getInstruction() != null && !businessEvent.getInstruction().isEmpty()) {
            System.out.println("[OK] Contains " + businessEvent.getInstruction().size() + " Instructions");
        } else {
            System.out.println("[WARN] No Instructions found");
        }
    }

    private void analyzeTradeState(TradeState tradeState) {
        System.out.println("\nTradeState Analysis:");
        
        // Check Trade
        if (tradeState.getTrade() != null) {
            System.out.println("[OK] Contains Trade");
            if (tradeState.getTrade().getParty() != null && !tradeState.getTrade().getParty().isEmpty()) {
                System.out.println("[OK] Contains " + tradeState.getTrade().getParty().size() + " Parties");
            } else {
                System.out.println("[WARN] No Parties found in Trade");
            }
        } else {
            System.out.println("[WARN] No Trade found");
        }
        
        // Check State
        if (tradeState.getState() != null) {
            System.out.println("[OK] Contains State: " + tradeState.getState().getPositionState());
        } else {
            System.out.println("[WARN] No State found");
        }
    }

    private void analyzeReportableEvent(ReportableEvent reportableEvent) {
        System.out.println("\nReportableEvent Analysis:");
        
        // Check OriginatingWorkflowStep
        if (reportableEvent.getOriginatingWorkflowStep() != null) {
            System.out.println("[OK] Contains OriginatingWorkflowStep");
            analyzeWorkflowStep(reportableEvent.getOriginatingWorkflowStep());
        } else {
            System.out.println("[WARN] No OriginatingWorkflowStep found");
        }
        
        // Check ReportableInformation
        if (reportableEvent.getReportableInformation() != null) {
            System.out.println("[OK] Contains ReportableInformation");
        } else {
            System.out.println("[WARN] No ReportableInformation found");
        }
    }

    void runReport(ReportableEvent reportableEvent) throws IOException {
        // TransactionReportInstruction from ReportableEvent and ReportingSide
        // For this example, arbitrarily PARTY_1 as the reporting party and PARTY_2 as the reporting counterparty
        final ReportingSide reportingSide = ReportingSide.builder()
                .setReportingParty(getCounterparty(reportableEvent, CounterpartyRoleEnum.PARTY_1))
                .setReportingCounterparty(getCounterparty(reportableEvent, CounterpartyRoleEnum.PARTY_2))
                .build();

        // Create transaction report instruction
        final Create_TransactionReportInstruction createInstructionFunc = injector.getInstance(Create_TransactionReportInstruction.class);
        final TransactionReportInstruction reportInstruction = createInstructionFunc.evaluate(reportableEvent, reportingSide);

        // Generate CFTC Part 45 report
        final CFTCPart45ReportFunction reportFunc = injector.getInstance(CFTCPart45ReportFunction.class);
        final CFTCPart45TransactionReport report = reportFunc.evaluate(reportInstruction);

        // Print the report
        System.out.println("\n=== Generated CFTC Part 45 Report ===");
        System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(report));
        System.out.println("===================================");
    }

    private ReferenceWithMetaParty getCounterparty(ReportableEvent reportableEvent, CounterpartyRoleEnum party) {
        try {
            // Extract directly from ReportableTrade
            if (reportableEvent.getReportableTrade() != null && 
                reportableEvent.getReportableTrade().getTrade() != null &&
                reportableEvent.getReportableTrade().getTrade().getParty() != null &&
                !reportableEvent.getReportableTrade().getTrade().getParty().isEmpty()) {
                
                List<? extends Party> parties = reportableEvent.getReportableTrade().getTrade().getParty();
                
                // Get the right party based on role
                Party targetParty = null;
                if (party == CounterpartyRoleEnum.PARTY_1 && parties.size() >= 1) {
                    targetParty = parties.get(0);
                } else if (party == CounterpartyRoleEnum.PARTY_2 && parties.size() >= 2) {
                    targetParty = parties.get(1);
                }
                
                if (targetParty != null) {
                    return ReferenceWithMetaParty.builder().setValue(targetParty).build();
                }
            }
        } catch (Exception e) {
            System.out.println("Warning: Could not extract party from ReportableTrade: " + e.getMessage());
        }
        
        // Fallback with dummy data if extraction fails
        String lei = party == CounterpartyRoleEnum.PARTY_1 ? "DUMMY0000000000LEI01" : "DUMMY0000000000LEI02";
        Party defaultParty = Party.builder()
            .addPartyId(PartyIdentifier.builder()
                .setIdentifier(FieldWithMetaString.builder()
                    .setValue(lei)
                    .setMeta(MetaFields.builder()
                        .setScheme("http://www.fpml.org/coding-scheme/external/iso17442")
                        .build())
                    .build())
                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                .build())
            .build();
        return ReferenceWithMetaParty.builder().setValue(defaultParty).build();
    }
} 
