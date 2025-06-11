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
 * It takes a JSON file as input (similar to CreateReportableEventAndRunReportExample.java).
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

        // Run example with a JSON input file
        String inputFile = args.length > 0 ? args[0] : "regulatory-reporting/input/events/New-Trade-01.json";
        generator.generateCFTCPart45Report(inputFile);
    }

    /**
     * Generate CFTC Part 45 report from a JSON input file
     *
     * @param inputJsonPath Path to the input JSON file (e.g., "regulatory-reporting/input/events/New-Trade-01.json")
     */
    public void generateCFTCPart45Report(String inputJsonPath) throws IOException {
        System.out.println("=== Generating CFTC Part 45 Report for Interest Rate Swap ===");
        System.out.println("Loading trade from: " + inputJsonPath);

        // 1. Load TradeState from JSON file and resolve references
        TradeState tradeState = ResourcesUtils.getObjectAndResolveReferences(
                TradeState.class, inputJsonPath);

        // 2. Create instruction for new trade execution
        WorkflowStep workflowStepInstruction = createNewTradeExecutionInstruction(tradeState);

        // 3. Create WorkflowStep with BusinessEvent representing new trade execution
        WorkflowStep workflowStep = postProcess(createWorkflowStep.evaluate(workflowStepInstruction));

        // 4. Convert WorkflowStep to ReportableEvent(s)
        List<? extends ReportableEvent> reportableEvents = createReportableEvents.evaluate(workflowStep);

        // 5. Add CFTC reporting information
        List<? extends ReportableEvent> reportableEventsWithInfo =
                reportableEvents.stream()
                        .map(reportableEvent -> reportableEvent.toBuilder()
                                .setReportableInformation(createReportableInformation())
                                .build())
                        .collect(Collectors.toList());

        // 6. Generate and print CFTC Part 45 reports
        System.out.println("Generated " + reportableEventsWithInfo.size() + " reportable event(s)");

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
     * Creates WorkflowStep instruction for new trade execution
     */
    private WorkflowStep createNewTradeExecutionInstruction(TradeState tradeState) {
        // Use the trade date from the trade state or default to the current date
        Date eventDate = tradeState.getTrade().getTradeDate() != null ?
                tradeState.getTrade().getTradeDate().getValue() :
                Date.of(2024, 1, 15);

        // Create instruction for new trade execution
        Instruction tradeStateInstruction = Instruction.builder()
                .setBeforeValue(tradeState)
                .setPrimitiveInstruction(PrimitiveInstruction.builder()
                        .setContractFormation(ContractFormationInstruction.builder()));

        // Create a workflow step with event instruction
        return WorkflowStep.builder()
                .setProposedEvent(EventInstruction.builder()
                        .addInstruction(tradeStateInstruction)
                        .setIntent(EventIntentEnum.CONTRACT_FORMATION)
                        .setEventDate(eventDate))
                .addTimestamp(EventTimestamp.builder()
                        .setDateTime(ZonedDateTime.of(eventDate.toLocalDate(),
                                LocalTime.of(9, 0), ZoneOffset.UTC.normalized()))
                        .setQualification(EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
                .addEventIdentifier(Identifier.builder()
                        .addAssignedIdentifier(AssignedIdentifier.builder()
                                .setIdentifier(FieldWithMetaString.builder()
                                        .setValue("IRS_NEW_TRADE_EXECUTION"))))
                .build();
    }

    /**
     * Creates CFTC-specific ReportableInformation
     */
    private ReportableInformation createReportableInformation() {
        // Create a reporting party
        Party reportingParty = Party.builder()
                .setMeta(MetaFields.builder().setExternalKey("reportingParty"))
                .setNameValue("Reporting Party")
                .addPartyId(PartyIdentifier.builder()
                        .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                        .setIdentifierValue("REPORTING_PARTY_LEI"))
                .build();

        // For this example, using Party1 as the reporting party
        return ReportableInformation.builder()
                .setConfirmationMethod(ConfirmationMethodEnum.ELECTRONIC) // Electronic confirmation
                .setExecutionVenueType(ExecutionVenueTypeEnum.SEF) // Swap Execution Facility
                .setLargeSizeTrade(false) // Not a large size trade
                .setPartyInformation(Collections.singletonList(
                        PartyInformation.builder()
                                .setPartyReferenceValue(reportingParty) // Using setPartyReferenceValue instead of setPartyReference
                                .setRegimeInformation(Collections.singletonList(
                                        ReportingRegime.builder()
                                                .setSupervisoryBodyValue(SupervisoryBodyEnum.CFTC)
                                                .setReportingRole(ReportingRoleEnum.REPORTING_PARTY)
                                                .setMandatorilyClearable(MandatorilyClearableEnum.PRODUCT_MANDATORY_BUT_NOT_CPTY)))))
                .build();
    }

    /**
     * Post-processing the function output, generates keys and runs qualification
     */
    @SuppressWarnings("unchecked")
    private <T extends RosettaModelObject> T postProcess(T object) {
        RosettaModelObjectBuilder builder = object.toBuilder();
        postProcessor.postProcess(builder.getType(), builder);
        return (T) builder;
    }
}
