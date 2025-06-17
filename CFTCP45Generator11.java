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
        // 1. Load and validate the CDM object
        WorkflowStep workflowStep = ResourcesUtils.getObjectAndResolveReferences(WorkflowStep.class, "regulatory-reporting/input/events/New-Trade-01.json");
        
        // 2. Analyze the CDM object structure
        CFTCP45Generator generator = new CFTCP45Generator();
        generator.analyzeCDMObject(workflowStep);
        
        // 3. Validate fields for DRR
        System.out.println("\n=== DRR Field Validation ===");
        generator.validateDRRFields(workflowStep);

        // 4. Create ReportableEvent
        System.out.println("\n=== Creating ReportableEvent ===");
        generator.createReportableEvent(workflowStep);
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
