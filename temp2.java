package com.regnosys.drr.examples;

import cdm.base.datetime.AdjustableDate;
import cdm.base.datetime.BusinessDayAdjustments;
import cdm.base.datetime.BusinessDayConventionEnum;
import cdm.base.datetime.BusinessDays;
import cdm.base.math.FinancialUnitEnum;
import cdm.base.math.UnitType;
import cdm.base.staticdata.asset.common.AssetClassEnum;
import cdm.base.staticdata.asset.common.ProductTaxonomy;
import cdm.base.staticdata.asset.rates.FloatingRateIndexEnum;
import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.*;
import cdm.event.common.*;
import cdm.event.workflow.EventInstruction;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.product.asset.InterestRatePayout;
import cdm.product.common.settlement.Cashflow;
import cdm.product.common.settlement.PaymentDate;
import cdm.product.template.EconomicTerms;
import cdm.product.template.Payout;
import cdm.product.template.Product;
import cdm.product.template.TradableProduct;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;
import com.regnosys.drr.examples.util.ResourcesUtils;
import com.regnosys.drr.function.Create_ReportableEvents;
import com.regnosys.drr.regulation.cftc.part45.report.CFTCPart45ExampleReport;
import com.regnosys.drr.regulation.common.functions.ReportableInformation;
import com.regnosys.drr.regulation.common.functions.ReportableInformationEnum;
import com.regnosys.drr.regulation.common.functions.SupervisoryBodyEnum;
import com.regnosys.rosetta.common.hashing.NonNullHashCollector;
import com.rosetta.model.lib.GlobalKey;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaString;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Example demonstrating creation of an execution trade and running CFTC Part 45 report
 */
public class CreateExecutionTradeAndRunCFTCPart45Report {

    @Inject
    private Create_ReportableEvents createReportableEvents;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java ...CreateExecutionTradeAndRunCFTCPart45Report <path-to-json>");
            System.exit(1);
        }
        String tradeStatePath = args[0];

        // Initialise guice for dependency injection
        Injector injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
        // Get dependency injected instance
        CreateExecutionTradeAndRunCFTCPart45Report example = injector.getInstance(CreateExecutionTradeAndRunCFTCPart45Report.class);

        // Run example with the provided path
        example.createExecutionTradeAndRunReport(tradeStatePath);
    }

    /**
     * Creates an execution trade and runs CFTC Part 45 reporting
     */
    public void createExecutionTradeAndRunReport(String tradeStatePath) throws IOException {
        // Load trade state from specified path
        TradeState originalTradeState = ResourcesUtils.getObjectAndResolveReferences(TradeState.class, tradeStatePath);
        
        // Create execution trade state
        TradeState executionTradeState = createExecutionTradeState(originalTradeState);

        // Get reportable information for CFTC Part 45
        ReportableInformation reportableInformation = getReportableInformationForCFTCPart45();

        // Create reportable events
        List<ReportableEvent> reportableEventsWithReportableInformation = createReportableEvents.evaluate(
                List.of(executionTradeState),
                List.of(reportableInformation)
        );

        // Generate CFTC Part 45 reports
        System.out.println("Generated " + reportableEventsWithReportableInformation.size() + " reportable events for CFTC Part 45:");
        reportableEventsWithReportableInformation.forEach(reportableEvent -> {
            try {
                CFTCPart45ExampleReport cftcPart45ExampleReport = new CFTCPart45ExampleReport();
                cftcPart45ExampleReport.runReport(reportableEvent);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Creates an execution trade state from the original trade state
     */
    private TradeState createExecutionTradeState(TradeState originalTradeState) {
        // Create execution timestamp (current time for this example)
        ZonedDateTime executionDateTime = ZonedDateTime.now(ZoneOffset.UTC);
        
        // Create event timestamp for execution
        EventTimestamp executionTimestamp = EventTimestamp.builder()
                .dateTime(executionDateTime)
                .qualification(EventTimestampQualificationEnum.EXECUTION_DATE_TIME)
                .build();

        // Create trade identifier for execution
        AssignedIdentifier tradeId = AssignedIdentifier.builder()
                .setIdentifierValue("EXEC-" + System.currentTimeMillis())
                .setAssignedIdentifierBuilder(AssignedIdentifier.AssignedIdentifierBuilder::getOrCreateIdentifier)
                .build();

        Identifier tradeIdentifier = Identifier.builder()
                .addAssignedIdentifier(tradeId)
                .build();

        // Create execution event instruction
        EventInstruction executionInstruction = EventInstruction.builder()
                .setIntent(EventIntentEnum.EXECUTION)
                .setCorporateActionIntent(CorporateActionTypeEnum.MANDATORY)
                .build();

        // Build execution event
        BusinessEvent executionEvent = BusinessEvent.builder()
                .setIntent(EventIntentEnum.EXECUTION)
                .addEventDate(Date.of(executionDateTime.toLocalDate()))
                .addEventTimestamp(executionTimestamp)
                .setInstruction(List.of(executionInstruction))
                .build();

        // Create workflow step for execution
        WorkflowStep executionWorkflowStep = WorkflowStep.builder()
                .setBusinessEvent(executionEvent)
                .addTimestamp(executionTimestamp)
                .build();

        // Build execution trade state
        return TradeState.builder()
                .setTrade(originalTradeState.getTrade().toBuilder()
                        .setTradeIdentifier(List.of(tradeIdentifier))
                        .setTradeDate(Date.of(executionDateTime.toLocalDate()))
                        .setExecutionDetails(ExecutionDetails.builder()
                                .setExecutionType(ExecutionTypeEnum.ELECTRONIC)
                                .setExecutionVenue(ExecutionVenue.builder()
                                        .setName(FieldWithMetaString.builder()
                                                .setValue("ELECTRONIC_TRADING_VENUE")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .addWorkflowStep(executionWorkflowStep)
                .build();
    }

    /**
     * Creates reportable information specific to CFTC Part 45
     */
    private ReportableInformation getReportableInformationForCFTCPart45() {
        // Create party information for reporting counterparty
        PartyInformation reportingParty = PartyInformation.builder()
                .setPartyReference(Party.builder()
                        .addPartyId(PartyIdentifier.builder()
                                .setIdentifierValue("54930084UKLVMY22DS16") // Sample LEI
                                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                                .build())
                        .setName(FieldWithMetaString.builder()
                                .setValue("REPORTING_PARTY_NAME")
                                .build())
                        .build())
                .addRegimeInformation(RegimeInformation.builder()
                        .setSupervisoryBody(SupervisoryBodyEnum.CFTC)
                        .setReportingRole(ReportingRoleEnum.REPORTING_COUNTERPARTY)
                        .setMandatorilyClearable(MandatorilyClearableEnum.NOT_SUBJECT_TO_CLEARING_OBLIGATION)
                        .setExceedsNotionalThreshold(ExceedsNotionalThresholdEnum.DOES_NOT_EXCEED)
                        .build())
                .build();

        // Create party information for non-reporting counterparty
        PartyInformation nonReportingParty = PartyInformation.builder()
                .setPartyReference(Party.builder()
                        .addPartyId(PartyIdentifier.builder()
                                .setIdentifierValue("48750084UKLVTR22DS78") // Sample LEI
                                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                                .build())
                        .setName(FieldWithMetaString.builder()
                                .setValue("NON_REPORTING_PARTY_NAME")
                                .build())
                        .build())
                .addRegimeInformation(RegimeInformation.builder()
                        .setSupervisoryBody(SupervisoryBodyEnum.CFTC)
                        .setReportingRole(ReportingRoleEnum.NON_REPORTING_COUNTERPARTY)
                        .setMandatorilyClearable(MandatorilyClearableEnum.NOT_SUBJECT_TO_CLEARING_OBLIGATION)
                        .setExceedsNotionalThreshold(ExceedsNotionalThresholdEnum.DOES_NOT_EXCEED)
                        .build())
                .build();

        // Build reportable information
        return ReportableInformation.builder()
                .setConfirmationMethod(ConfirmationMethodEnum.ELECTRONIC_CONFIRMATION)
                .setExecutionVenueType(ExecutionVenueTypeEnum.ELECTRONIC_TRADING_VENUE)
                .setLargeSizeTrade(false)
                .addPartyInformation(reportingParty)
                .addPartyInformation(nonReportingParty)
                .build();
    }
} 
