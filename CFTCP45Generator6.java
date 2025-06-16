package com.regnosys.drr.examples;

import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.event.common.BusinessEvent;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.workflow.WorkflowStep;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;
import com.regnosys.drr.examples.util.ResourcesUtils;
import com.regnosys.rosetta.common.serialisation.RosettaObjectMapper;
import drr.enrichment.common.trade.functions.Create_TransactionReportInstruction;
import drr.regulation.cftc.rewrite.CFTCPart45TransactionReport;
import drr.regulation.cftc.rewrite.reports.CFTCPart45ReportFunction;
import drr.regulation.common.ReportableEvent;
import drr.regulation.common.ReportingSide;
import drr.regulation.common.TransactionReportInstruction;
import drr.regulation.common.functions.ExtractTradeCounterparty;

import java.io.IOException;
import java.util.List;

public class CFTCP45Generator {

    public static void main(String[] args) throws IOException {
        // 1. Load and validate the CDM object
        WorkflowStep workflowStep = ResourcesUtils.getObjectAndResolveReferences(WorkflowStep.class, "regulatory-reporting/input/events/InterestRateSwap-Termination-01.json");
        
        // 2. Analyze the CDM object structure
        CFTCP45Generator generator = new CFTCP45Generator();
        generator.analyzeCDMObject(workflowStep);
        
        // 3. Validate fields for DRR
        System.out.println("\n=== DRR Field Validation ===");
        generator.validateDRRFields(workflowStep);
    }

    private final Injector injector;

    CFTCP45Generator() {
        this.injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
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
        ExtractTradeCounterparty func = injector.getInstance(ExtractTradeCounterparty.class);
        return func.evaluate(reportableEvent, party).getPartyReference();
    }
} 
