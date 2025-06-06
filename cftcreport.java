package com.regnosys.drr.examples;

import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.event.workflow.EventInstruction;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowStep;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;
import com.regnosys.drr.examples.util.ResourcesUtils;
import com.regnosys.rosetta.common.postprocess.WorkflowPostProcessor;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.rosetta.model.lib.records.Date;
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
 * This example demonstrates how to:
 * - Create a WorkflowStep for a new trade execution
 * - Create a ReportableEvent from the WorkflowStep
 * - Generate a CFTCPart45TransactionReport
 */
public class CreateExecutionTradeReportExample {

    @Inject Create_ReportableEvents createReportableEvents;
    @Inject WorkflowPostProcessor postProcessor;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java CreateExecutionTradeReportExample <path-to-trade-json>");
            System.exit(1);
        }
        String tradePath = args[0];

        // Initialize Guice for dependency injection
        Injector injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
        CreateExecutionTradeReportExample example = injector.getInstance(CreateExecutionTradeReportExample.class);

        // Run example with provided trade file
        example.createExecutionTradeAndReport(tradePath);
    }

    void createExecutionTradeAndReport(String tradePath) throws IOException {
        // 1. Load the trade state from JSON file
        TradeState tradeState = ResourcesUtils.getObjectAndResolveReferences(TradeState.class, tradePath);

        // 2. Create workflow step for trade execution
        WorkflowStep workflowStep = getExecutionWorkflowStep(tradeState);

        // 3. Create reportable events from workflow step
        List<? extends ReportableEvent> reportableEvents = createReportableEvents.evaluate(workflowStep);

        // 4. Add reportable information to events
        List<? extends ReportableEvent> reportableEventsWithInfo =
            reportableEvents.stream()
                .map(event -> event.toBuilder()
                    .setReportableInformation(getReportableInformation())
                    .build())
                .collect(Collectors.toList());

        // 5. Generate CFTC Part 45 reports
        reportableEventsWithInfo.forEach(event -> {
            try {
                CFTCPart45ExampleReport report = new CFTCPart45ExampleReport();
                report.runReport(event);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private WorkflowStep getExecutionWorkflowStep(TradeState tradeState) {
        Date eventDate = Date.of(2024, 4, 29); // Today's date

        // Create execution instruction
        Instruction executionInstruction = Instruction.builder()
            .setBeforeValue(tradeState)
            .setPrimitiveInstruction(PrimitiveInstruction.builder()
                .setExecution(ExecutionInstruction.builder()
                    .build()))
            .build();

        // Create workflow step with execution event
        return WorkflowStep.builder()
            .setProposedEvent(EventInstruction.builder()
                .addInstruction(executionInstruction)
                .setIntent(EventIntentEnum.EXECUTION)
                .setEventDate(eventDate))
            .addTimestamp(EventTimestamp.builder()
                .setDateTime(ZonedDateTime.of(eventDate.toLocalDate(), LocalTime.now(), ZoneOffset.UTC))
                .setQualification(EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
            .addEventIdentifier(Identifier.builder()
                .addAssignedIdentifier(AssignedIdentifier.builder()
                    .setIdentifierValue("EXECUTION-" + System.currentTimeMillis())))
            .build();
    }

    private ReportableInformation getReportableInformation() {
        return ReportableInformation.builder()
            .setConfirmationMethod(ConfirmationMethodEnum.ELECTRONIC)
            .setExecutionVenueType(ExecutionVenueTypeEnum.SEF)
            .setLargeSizeTrade(false)
            .setPartyInformation(Collections.singletonList(
                PartyInformation.builder()
                    .setPartyReferenceValue(getParty())
                    .setRegimeInformation(Collections.singletonList(
                        ReportingRegime.builder()
                            .setSupervisoryBodyValue(SupervisoryBodyEnum.CFTC)
                            .setReportingRole(ReportingRoleEnum.REPORTING_PARTY)
                            .setMandatorilyClearable(MandatorilyClearableEnum.PRODUCT_MANDATORY_BUT_NOT_CPTY)
                            .build()))
                    .build()))
            .build();
    }

    private Party getParty() {
        return Party.builder()
            .setMeta(MetaFields.builder().setExternalKey("party1"))
            .setNameValue("Bank A")
            .addPartyId(PartyIdentifier.builder()
                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                .setIdentifierValue("LEI_BANK_A"))
            .build();
    }

    private <T extends RosettaModelObject> T postProcess(T obj) {
        RosettaModelObjectBuilder builder = obj.toBuilder();
        postProcessor.postProcess(builder.getType(), builder);
        return (T) builder.build();
    }
}
