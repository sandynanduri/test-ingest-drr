package com.regnosys.drr.examples;

import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.*;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.product.template.TradableProduct;
import cdm.event.common.*;
import cdm.event.workflow.EventInstruction;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.functions.Create_AcceptedWorkflowStepFromInstruction;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;
import com.regnosys.drr.examples.util.ResourcesUtils;
import com.regnosys.rosetta.common.postprocess.WorkflowPostProcessor;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaString;
import drr.regulation.common.*;
import drr.enrichment.common.trade.functions.Create_ReportableEvents;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.ArrayList;

/**
 * This class demonstrates how to create CFTC Part 45 reports for Interest Rate Swap new trade executions.
 * It follows the same pattern as CreateReportableEventAndRunReportExample.java.
 * Key features:
 * - Loads TradeState from a JSON file
 * - Creates a WorkflowStep with CONTRACT_FORMATION intent
 * - Generates ReportableEvent from WorkflowStep
 * - Creates CFTC Part 45 Transaction Report

 * Note: All CFTC Part 45 fields (Day Count, Payment Frequency, Reset Frequency, Fixed Rate, Spread, etc.)
 * are automatically extracted and populated by the DRR framework through Rosetta reporting rules.
 * Manual field extraction is not required for the actual regulatory report.
 */
public class CFTCPart45InterestRateSwapNewTradeGenerator {

    @Inject Create_AcceptedWorkflowStepFromInstruction createWorkflowStep;
    @Inject Create_ReportableEvents createReportableEvents;
    @Inject WorkflowPostProcessor postProcessor;

    public static void main(String[] args) {
        // Initialize Guice for dependency injection
        Injector injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());

        // Get dependency injected instance
        CFTCPart45InterestRateSwapNewTradeGenerator generator =
                injector.getInstance(CFTCPart45InterestRateSwapNewTradeGenerator.class);

        // Support command line argument for file-path
        if (args.length > 0) {
            System.out.println("=== CFTC Part 45 Interest Rate Swap Report Generator ===");
            System.out.println("Using input file from command line: " + args[0]);
            
            // Print file content
            try {
                System.out.println("\n=== File Content ===");
                String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])));
                System.out.println(content);
                System.out.println("=== End of File Content ===\n");
            } catch (Exception e) {
                System.out.println("Error reading file: " + e.getMessage());
            }
            
            generator.createReportableEventAndRunReport(args[0]);
        } else {
            // Run the CFTC Part 45 report generation with a default file.
            System.out.println("=== CFTC Part 45 Interest Rate Swap Report Generator ===");
            System.out.println("Usage: java CFTCPart45InterestRateSwapNewTradeGenerator [path to input file]");
            System.out.println("Running with default file...");
            generator.createReportableEventAndRunReport();
        }
    }

    /**
     * This method demonstrates how to create a ReportableEvent from a WorkflowStep, and then create a CFTCPart45TransactionReport.
     * It follows the same pattern as CreateReportableEventAndRunReportExample.java.
     * All CFTC Part 45 fields are automatically extracted by the DRR framework:
     * - Field 53: Day Count Convention (Fixed/Floating Rate legs)
     * - Fields 55-56: Floating Rate Reset Frequency Period/Multiplier
     * - Fields 61-64: Payment Frequency Period/Multiplier (Fixed/Floating Rate legs)
     * - Field 67: Fixed Rate Value
     * - Fields 73-75: Spread Value/Currency/Notation
     * - And 80+ other CFTC Part 45 fields
     */
    void createReportableEventAndRunReport() {
        // Use the default file path
        String defaultFilePath = "result-json-files/fpml-5-10/products/rates/USD-Vanilla-swap.json";
        createReportableEventAndRunReport(defaultFilePath);
    }

    /**
     * Overloaded method that accepts a custom file path for flexible input handling.
     */
    void createReportableEventAndRunReport(String inputFilePath) {
        System.out.println("=== Generating CFTC Part 45 Report for Interest Rate Swap ===");

        // 1. Trade to be used for new trade execution with smart auto-detection
        System.out.println("Input file: " + inputFilePath);
        System.out.println("Checking if file exists...");
        java.io.File file = new java.io.File(inputFilePath);
        System.out.println("File exists: " + file.exists());
        System.out.println("Absolute path: " + file.getAbsolutePath());
        
        // Smart auto-detection and extraction of TradeState
        TradeState originalTradeState;
        try {
            originalTradeState = loadTradeStateFromAnyFile(inputFilePath);
        } catch (Exception e) {
            System.err.println("FAILED to load TradeState from file: " + e.getMessage());
            System.err.println("Suggestion: Run CDMStructureValidator on your file for detailed analysis:");
            System.err.println("  java com.regnosys.drr.examples.util.CDMStructureValidator \"" + inputFilePath + "\"");
            throw e;
        }

        // Fix common counterparty issues that cause ExtractTradeCounterparty to return null
        System.out.println("Checking and fixing counterparty structure for DRR compatibility...");
        TradeState tradeState = fixCounterpartyIssues(originalTradeState);

        // Debug: Let's examine what we actually loaded
        System.out.println("Successfully loaded TradeState for new trade execution");
        System.out.println("TradeState class: " + tradeState.getClass().getName());
        System.out.println("TradeState toString: " + tradeState);
        System.out.println("TradeState.getTrade() is null: " + (tradeState.getTrade() == null));
        if (tradeState.getTrade() == null) {
            System.out.println("ERROR: TradeState.getTrade() returned null!");
            System.out.println("Available fields in TradeState:");
            System.out.println("- State: " + tradeState.getState());
            System.out.println("- Meta: " + tradeState.getMeta());
            // Check if there are any other ways to access the trade data
            throw new IllegalStateException("TradeState.getTrade() is null - the JSON file may not contain a Trade object or may be structured differently");
        }

        // 2. Create instructions for new trade execution
        WorkflowStep workflowStepInstruction = getNewTradeExecutionInstruction(tradeState);

        // 3. Invoke function to create a WorkflowStep that contains a BusinessEvent that represents a new trade execution
        WorkflowStep workflowStep = postProcess(createWorkflowStep.evaluate(workflowStepInstruction));

        // 4. Invoke function to convert a WorkflowStep into a list of ReportableEvents
        List<? extends ReportableEvent> reportableEvents = createReportableEvents.evaluate(workflowStep);

        System.out.println("Generated " + reportableEvents.size() + " reportable event(s)");

        // 5. Before creating the transaction report, the ReportableInformation should be added to the ReportableEvent
        List<? extends ReportableEvent> reportableEventsWithInfo =
                reportableEvents.stream()
                        .map(reportableEvent -> reportableEvent.toBuilder()
                                .setReportableInformation(getReportableInformation(tradeState))
                                .build())
                        .collect(Collectors.toList());

        // 6. For each ReportableEvent, create and print the CFTCPart45TransactionReport
        // The DRR framework automatically extracts all required CFTC Part 45 fields
        reportableEventsWithInfo.forEach(reportableEvent -> {
            try {
                System.out.println("--- Generating CFTC Part 45 Report ---");
                CFTCPart45ExampleReport cftcReport = new CFTCPart45ExampleReport();
                cftcReport.runReport(reportableEvent);
                System.out.println("Successfully generated CFTC Part 45 report with all required fields");
                System.out.println("Note: All rate-related fields (Day Count, Payment/Reset Frequency, Fixed Rate, Spread) are automatically populated by DRR framework");
            } catch (IOException e) {
                System.err.println("Error generating report: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Creates function input for a New Trade Execution event, i.e., before TradeState and Instructions.
     *
     * @return WorkflowStep containing a proposed EventInstruction for a new trade execution
     * @param tradeState The base trade state to use
     */
    private WorkflowStep getNewTradeExecutionInstruction(TradeState tradeState) {
        // Get event date following DRR rules
        Date eventDate;
        LocalTime eventTime;
        ZoneOffset eventZone;

        // For Interest Rate Swaps, use tradeDate from TradeState
        if (tradeState.getTrade().getTradeDate() != null) {
            eventDate = tradeState.getTrade().getTradeDate().getValue();
        }
        // If tradeDate is not available, use tradeTime
        else if (tradeState.getTrade().getTradeTime() != null && tradeState.getTrade().getTradeTime().getValue() != null) {
            // Use current date since TimeZone only contains time information
            eventDate = Date.of(LocalDate.now());
        }
        // If neither is available, use the current date (as a last resort)
        else {
            eventDate = Date.of(LocalDate.now());
        }

        // Get execution time from tradeTime if available
        if (tradeState.getTrade().getTradeTime() != null && tradeState.getTrade().getTradeTime().getValue() != null) {
            eventTime = tradeState.getTrade().getTradeTime().getValue().getTime();
            // Get timezone from location if available, otherwise use UTC
            eventZone = tradeState.getTrade().getTradeTime().getValue().getLocation() != null ?
                    ZoneOffset.of(tradeState.getTrade().getTradeTime().getValue().getLocation().getValue()) :
                    ZoneOffset.UTC;
        } else {
            // Use current time and UTC timezone as defaults when trade time is not available
            eventTime = LocalTime.now();
            eventZone = ZoneOffset.UTC;
        }

        // Create an Instruction that contains:
        // - before TradeState
        // - PrimitiveInstruction containing a ContractFormation
        Instruction tradeStateInstruction = Instruction.builder()
                .setBeforeValue(tradeState)
                .setPrimitiveInstruction(PrimitiveInstruction.builder()
                        .setContractFormation(ContractFormationInstruction.builder()));

        // Create a workflow step instruction containing the EventInstruction, EventTimestamp and EventIdentifiers
        // Note: EventIntentEnum.CONTRACT_FORMATION is required for new trade executions per business rules
        return WorkflowStep.builder()
                .setProposedEvent(EventInstruction.builder()
                        .addInstruction(tradeStateInstruction)
                        .setIntent(EventIntentEnum.CONTRACT_FORMATION)
                        .setEventDate(eventDate))
                .addTimestamp(EventTimestamp.builder()
                        .setDateTime(ZonedDateTime.of(eventDate.toLocalDate(), eventTime, eventZone))
                        .setQualification(EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
                .addEventIdentifier(Identifier.builder()
                        .addAssignedIdentifier(AssignedIdentifier.builder()
                                .setIdentifier(FieldWithMetaString.builder()
                                        .setValue(tradeState.getTrade().getTradeIdentifier().get(0).getAssignedIdentifier().get(0).getIdentifier().getValue()))))
                .build();
    }

    /**
     * Creates the reporting party from the CDM TradeState.
     */
    private Party getParty(TradeState tradeState) {
        List<? extends Party> parties = tradeState.getTrade().getParty();
        if (parties == null || parties.isEmpty()) {
            throw new IllegalStateException("No parties found in trade");
        }
        // For CFTC Part 45 reporting, we need to select the appropriate reporting party
        // This could be enhanced with more sophisticated logic based on party roles or other criteria
        return parties.get(0);
    }

    /**
     * ReportableEvent requires ReportableInformation to specify data such as which party is the reporting party.
     * Note: The values below are hardcoded because the current Java model does not expose
     * PartyTradeInformation or TradeHeader methods on the Trade class.
     */
    private ReportableInformation getReportableInformation(TradeState tradeState) {
        return ReportableInformation.builder()
                .setConfirmationMethod(ConfirmationMethodEnum.ELECTRONIC)
                .setExecutionVenueType(ExecutionVenueTypeEnum.SEF)
                .setLargeSizeTrade(false)
                .setPartyInformation(Collections.singletonList(PartyInformation.builder()
                        .setPartyReferenceValue(getParty(tradeState))
                        .setRegimeInformation(Collections.singletonList(ReportingRegime.builder()
                                .setSupervisoryBodyValue(SupervisoryBodyEnum.CFTC)
                                .setReportingRole(ReportingRoleEnum.REPORTING_PARTY)
                                .setMandatorilyClearable(MandatorilyClearableEnum.PRODUCT_MANDATORY_BUT_NOT_CPTY)
                                .build()))
                        .build()))
                .build();
    }

    /**
     * Post-processing the function output, generates keys on any new objects, and runs qualification.
     */
    @SuppressWarnings("unchecked")
    private <T extends RosettaModelObject> T postProcess(T object) {
        RosettaModelObjectBuilder builder = object.toBuilder();
        postProcessor.postProcess(builder.getType(), builder);
        return (T) builder;
    }

    /**
     * Smart auto-detection method that can load TradeState from any CDM file type.
     * Supports: TradeState, WorkflowStep, ReportableEvent, BusinessEvent, Trade
     */
    private TradeState loadTradeStateFromAnyFile(String filePath) {
        System.out.println("Attempting to detect file type and extract TradeState...");
        System.out.println("\n=== Input File Content ===");
        try {
            String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
            System.out.println(content);
            System.out.println("=== End of Input File ===\n");
        } catch (Exception e) {
            System.out.println("Could not read file: " + e.getMessage());
        }
        
        // Strategy 1: Try loading as TradeState directly
        try {
            TradeState tradeState = ResourcesUtils.getObjectAndResolveReferences(TradeState.class, filePath);
            if (tradeState.getTrade() != null) {
                System.out.println("✓ Successfully loaded as TradeState");
                return tradeState;
            } else {
                System.out.println("⚠ Loaded as TradeState but getTrade() is null");
            }
        } catch (Exception e) {
            System.out.println("→ Not a TradeState file: " + e.getMessage());
        }

        // Strategy 2: Try loading as WorkflowStep and extract from BusinessEvent
        try {
            WorkflowStep workflowStep = ResourcesUtils.getObjectAndResolveReferences(WorkflowStep.class, filePath);
            if (workflowStep.getBusinessEvent() != null && 
                workflowStep.getBusinessEvent().getAfter() != null && 
                !workflowStep.getBusinessEvent().getAfter().isEmpty()) {
                
                TradeState tradeState = workflowStep.getBusinessEvent().getAfter().get(0);
                if (tradeState.getTrade() != null) {
                    System.out.println("✓ Successfully extracted TradeState from WorkflowStep.businessEvent.after[0]");
                    return tradeState;
                } else {
                    System.out.println("⚠ Found TradeState in WorkflowStep but getTrade() is null");
                }
            }
        } catch (Exception e) {
            System.out.println("→ Not a WorkflowStep file: " + e.getMessage());
        }

        // Strategy 3: Try loading as ReportableEvent and extract from a workflow step
        try {
            ReportableEvent reportableEvent = ResourcesUtils.getObjectAndResolveReferences(ReportableEvent.class, filePath);
            if (reportableEvent.getOriginatingWorkflowStep() != null) {
                WorkflowStep workflowStep = reportableEvent.getOriginatingWorkflowStep();
                
                // Try from business event after states
                if (workflowStep.getBusinessEvent() != null && 
                    workflowStep.getBusinessEvent().getAfter() != null && 
                    !workflowStep.getBusinessEvent().getAfter().isEmpty()) {
                    
                    TradeState tradeState = workflowStep.getBusinessEvent().getAfter().get(0);
                    if (tradeState.getTrade() != null) {
                        System.out.println("✓ Successfully extracted TradeState from ReportableEvent.originatingWorkflowStep.businessEvent.after[0]");
                        return tradeState;
                    }
                }
                
                // Try from reportable trade
                if (reportableEvent.getReportableTrade() != null && 
                    reportableEvent.getReportableTrade().getTrade() != null) {
                    System.out.println("✓ Successfully extracted TradeState from ReportableEvent.reportableTrade");
                    return reportableEvent.getReportableTrade();
                }
            }
        } catch (Exception e) {
            System.out.println("→ Not a ReportableEvent file: " + e.getMessage());
        }

        // Strategy 4: Try loading as BusinessEvent directly
        try {
            BusinessEvent businessEvent = ResourcesUtils.getObjectAndResolveReferences(BusinessEvent.class, filePath);
            if (businessEvent.getAfter() != null && !businessEvent.getAfter().isEmpty()) {
                TradeState tradeState = businessEvent.getAfter().get(0);
                if (tradeState.getTrade() != null) {
                    System.out.println("✓ Successfully extracted TradeState from BusinessEvent.after[0]");
                    return tradeState;
                }
            }
        } catch (Exception e) {
            System.out.println("→ Not a BusinessEvent file: " + e.getMessage());
        }

        // Strategy 5: Try loading as Trade directly and wrap in TradeState
        try {
            Trade trade = ResourcesUtils.getObjectAndResolveReferences(Trade.class, filePath);
            if (trade != null) {
                TradeState tradeState = TradeState.builder()
                    .setTrade(trade)
                    .build();
                System.out.println("✓ Successfully loaded Trade and wrapped in TradeState");
                return tradeState;
            }
        } catch (Exception e) {
            System.out.println("→ Not a Trade file: " + e.getMessage());
        }

        // If all strategies fail, provide helpful guidance
        System.out.println("✗ FAILED: Could not extract a valid TradeState from any supported CDM type");
        System.out.println("Supported file types:");
        System.out.println("  - TradeState (direct)");
        System.out.println("  - WorkflowStep (extracts from businessEvent.after[0])");
        System.out.println("  - ReportableEvent (extracts from originatingWorkflowStep or reportableTrade)");
        System.out.println("  - BusinessEvent (extracts from after[0])");
        System.out.println("  - Trade (wraps in TradeState)");
        System.out.println("Please check your file structure using CDMStructureValidator for detailed analysis.");
        
        throw new IllegalStateException("Unable to extract TradeState from the file: " + filePath + 
            ". Use CDMStructureValidator to analyze your file structure.");
    }

    /**
     * Fixes missing or invalid counterparty structures that cause ExtractTradeCounterparty to return null.
     * This is a common issue when loading files that don't have proper DRR-compatible counterparty setup.
     */
    private TradeState fixCounterpartyIssues(TradeState tradeState) {
        Trade trade = tradeState.getTrade();
        
        // Check if counterparties exist and are properly structured
        if (trade.getTradableProduct() != null && 
            trade.getTradableProduct().getCounterparty() != null && 
            !trade.getTradableProduct().getCounterparty().isEmpty()) {
            
            // Counterparties exist, check if they have proper references
            boolean hasValidCounterparties = trade.getTradableProduct().getCounterparty().stream()
                .anyMatch(cp -> cp.getPartyReference() != null && cp.getRole() != null);
                
            if (hasValidCounterparties) {
                System.out.println("  [OK] Counterparties are properly structured");
                return tradeState;
            }
        }
        
        System.out.println("  [FIXING] Adding missing counterparty structure for DRR compatibility...");
        
        // Create counterparties from root-level parties if they exist
        if (trade.getParty() != null && trade.getParty().size() >= 2) {
            List<Counterparty> counterparties = new ArrayList<>();
            
            // Create Party 1
            counterparties.add(Counterparty.builder()
                .setRole(CounterpartyRoleEnum.PARTY_1)
                .setPartyReference(ReferenceWithMetaParty.builder()
                    .setValue(trade.getParty().get(0))
                    .build())
                .build());
                
            // Create Party 2  
            counterparties.add(Counterparty.builder()
                .setRole(CounterpartyRoleEnum.PARTY_2)
                .setPartyReference(ReferenceWithMetaParty.builder()
                    .setValue(trade.getParty().get(1))
                    .build())
                .build());
                
            // Create updated TradableProduct with counterparties
            TradableProduct updatedTradableProduct = trade.getTradableProduct().toBuilder()
                .setCounterparty(counterparties)
                .build();
                
            // Create updated Trade
            Trade updatedTrade = trade.toBuilder()
                .setTradableProduct(updatedTradableProduct)
                .build();
                
            // Create updated TradeState
            TradeState updatedTradeState = tradeState.toBuilder()
                .setTrade(updatedTrade)
                .build();
                
            System.out.println("  [OK] Successfully added counterparty structure (PARTY_1, PARTY_2)");
            return updatedTradeState;
            
        } else if (trade.getParty() != null && trade.getParty().size() == 1) {
            // Only one party - create a minimal structure
            List<Counterparty> counterparties = new ArrayList<>();
            
            counterparties.add(Counterparty.builder()
                .setRole(CounterpartyRoleEnum.PARTY_1)
                .setPartyReference(ReferenceWithMetaParty.builder()
                    .setValue(trade.getParty().get(0))
                    .build())
                .build());
                
            TradableProduct updatedTradableProduct = trade.getTradableProduct().toBuilder()
                .setCounterparty(counterparties)
                .build();
                
            Trade updatedTrade = trade.toBuilder()
                .setTradableProduct(updatedTradableProduct)
                .build();
                
            TradeState updatedTradeState = tradeState.toBuilder()
                .setTrade(updatedTrade)
                .build();
                
            System.out.println("  [OK] Successfully added counterparty structure (PARTY_1 only)");
            return updatedTradeState;
        } else {
            System.out.println("  [WARN] Cannot fix counterparties - no root-level parties found");
            System.out.println("  [INFO] This may cause ExtractTradeCounterparty to return null");
            return tradeState;
        }
    }
}
