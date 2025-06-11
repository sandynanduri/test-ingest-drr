package com.regnosys.drr.examples;

import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.*;
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
import com.rosetta.model.metafields.MetaFields;
import drr.regulation.common.*;
import drr.enrichment.common.trade.functions.Create_ReportableEvents;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class demonstrates how to create CFTC Part 45 reports for Interest Rate Swap new trade executions.
 * It follows the same pattern as CreateReportableEventAndRunReportExample.java.
 * 
 * Key features:
 * - Loads TradeState from a JSON file
 * - Creates a WorkflowStep with CONTRACT_FORMATION intent
 * - Generates ReportableEvent from WorkflowStep
 * - Creates CFTC Part 45 Transaction Report
 */
public class CFTCPart45InterestRateSwapNewTradeGenerator {

    @Inject Create_AcceptedWorkflowStepFromInstruction createWorkflowStep;
    @Inject Create_ReportableEvents createReportableEvents;
    @Inject WorkflowPostProcessor postProcessor;

    public static void main(String[] args) throws IOException {
        // Initialize Guice for dependency injection
        Injector injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());

        // Get dependency injected instance
        CFTCPart45InterestRateSwapNewTradeGenerator generator = 
                injector.getInstance(CFTCPart45InterestRateSwapNewTradeGenerator.class);

        // Run example
        generator.createReportableEventAndRunReport();
    }

    /**
     * This method demonstrates how to create a ReportableEvent from a WorkflowStep, and then create a CFTCPart45TransactionReport.
     * It follows the same pattern as CreateReportableEventAndRunReportExample.java.
     */
    void createReportableEventAndRunReport() throws IOException {
        System.out.println("=== Generating CFTC Part 45 Report for Interest Rate Swap ===");

        // 1. Trade to be used for new trade execution. Note that all references are resolved here.
        TradeState tradeState = ResourcesUtils.getObjectAndResolveReferences(
                TradeState.class, "result-json-files/fpml-5-10/products/rates/USD-Vanilla-swap.json");
        
        System.out.println("Successfully loaded TradeState for new trade execution");

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
                                .setReportableInformation(getReportableInformation())
                                .build())
                        .collect(Collectors.toList());

        // 6. For each ReportableEvent, create and print the CFTCPart45TransactionReport
        reportableEventsWithInfo.forEach(reportableEvent -> {
            try {
                System.out.println("--- Generating CFTC Part 45 Report ---");
                CFTCPart45ExampleReport cftcReport = new CFTCPart45ExampleReport();
                cftcReport.runReport(reportableEvent);
                System.out.println("Successfully generated CFTC Part 45 report");
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
        // Use a fixed event date for this example
        Date eventDate = Date.of(2024, 1, 15);

        // Create an Instruction that contains:
        // - before TradeState
        // - PrimitiveInstruction containing a ContractFormation
        Instruction tradeStateInstruction = Instruction.builder()
                .setBeforeValue(tradeState)
                .setPrimitiveInstruction(PrimitiveInstruction.builder()
                        .setContractFormation(ContractFormationInstruction.builder()));

        // Create a workflow step instruction containing the EventInstruction, EventTimestamp and EventIdentifiers
        return WorkflowStep.builder()
                .setProposedEvent(EventInstruction.builder()
                        .addInstruction(tradeStateInstruction)
                        .setIntent(EventIntentEnum.CONTRACT_FORMATION)
                        .setEventDate(eventDate))
                .addTimestamp(EventTimestamp.builder()
                        .setDateTime(ZonedDateTime.of(eventDate.toLocalDate(), LocalTime.of(9, 0), ZoneOffset.UTC.normalized()))
                        .setQualification(EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
                .addEventIdentifier(Identifier.builder()
                        .addAssignedIdentifier(AssignedIdentifier.builder()
                                .setIdentifier(FieldWithMetaString.builder()
                                        .setValue("IRS_NEW_TRADE_EXECUTION"))))
                .build();
    }

    /**
     * ReportableEvent requires ReportableInformation to specify data such as which party is the reporting party.
     */
    private ReportableInformation getReportableInformation() {
        return ReportableInformation.builder()
                .setConfirmationMethod(ConfirmationMethodEnum.ELECTRONIC)
                .setExecutionVenueType(ExecutionVenueTypeEnum.SEF)
                .setLargeSizeTrade(false)
                .setPartyInformation(Collections.singletonList(PartyInformation.builder()
                        .setPartyReferenceValue(getParty())
                        .setRegimeInformation(Collections.singletonList(ReportingRegime.builder()
                                .setSupervisoryBodyValue(SupervisoryBodyEnum.CFTC)
                                .setReportingRole(ReportingRoleEnum.REPORTING_PARTY)
                                .setMandatorilyClearable(MandatorilyClearableEnum.PRODUCT_MANDATORY_BUT_NOT_CPTY)))))
                .build();
    }

    /**
     * Creates the reporting party.
     */
    private Party getParty() {
        return Party.builder()
                .setMeta(MetaFields.builder().setExternalKey("reportingParty"))
                .setNameValue("Reporting Party")
                .addPartyId(PartyIdentifier.builder()
                        .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                        .setIdentifierValue("REPORTING_PARTY_LEI"))
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
}
