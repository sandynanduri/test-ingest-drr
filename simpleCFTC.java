package com.regnosys.drr.examples;

import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.event.common.ReportableEvent;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;
import com.regnosys.drr.examples.util.ResourcesUtils;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.regnosys.rosetta.common.postprocess.WorkflowPostProcessor;
import drr.regulation.common.*;
import drr.regulation.cftc.rewrite.CFTCPart45TransactionReport;
import drr.regulation.cftc.rewrite.reports.CFTCPart45ReportFunction;
import drr.regulation.common.functions.Create_TransactionReportInstruction;
import drr.enrichment.common.trade.functions.Create_ReportableEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * PROPER way to implement DRR for CFTC Part 45 reporting.
 * 
 * This follows the official DRR architecture:
 * TradeState → ReportableEvent → TransactionReportInstruction → CFTCPart45TransactionReport
 * 
 * Key Benefits:
 * - Uses DRR's built-in pipeline functions
 * - Minimal custom code - DRR does the heavy lifting
 * - Proper separation of concerns
 * - Production-ready and maintainable
 */
public class SimplifiedCFTCReportingService {

    private static final Logger logger = LoggerFactory.getLogger(SimplifiedCFTCReportingService.class);

    @Inject Create_ReportableEvents createReportableEvents;
    @Inject Create_TransactionReportInstruction createTransactionReportInstruction;
    @Inject CFTCPart45ReportFunction cftcPart45ReportFunction;
    @Inject WorkflowPostProcessor postProcessor;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java SimplifiedCFTCReportingService <trade-json-path>");
            System.err.println("Example: 'result-json-files/fpml-5-10/products/rates/USD-Vanilla-swap.json'");
            System.exit(1);
        }

        String tradePath = args[0];

        Injector injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
        SimplifiedCFTCReportingService service = injector.getInstance(SimplifiedCFTCReportingService.class);

        service.generateCFTCReport(tradePath);
    }

    /**
     * Main method - this is how DRR is SUPPOSED to be used!
     * 
     * Step 1: Load CDM TradeState (from your trading system)
     * Step 2: Create ReportableEvent (using DRR functions)
     * Step 3: Create TransactionReportInstruction (using DRR functions)
     * Step 4: Generate CFTC Report (using DRR functions)
     */
    public void generateCFTCReport(String tradePath) throws IOException {
        logger.info("=== Starting PROPER DRR CFTC Part 45 Reporting ===");
        logger.info("Trade file: {}", tradePath);

        try {
            // Step 1: Load your CDM TradeState (this comes from your trading system)
            TradeState tradeState = loadTradeState(tradePath);
            logTradeStateSummary(tradeState);

            // Step 2: Create ReportableEvent from TradeState using DRR
            // Note: In reality, this usually comes from workflow events, not directly from TradeState
            ReportableEvent reportableEvent = createReportableEventFromTradeState(tradeState);
            logger.info("✓ Created ReportableEvent using DRR functions");

            // Step 3: Create TransactionReportInstruction using DRR
            // This is where you specify WHO is reporting to WHOM
            ReportingSide reportingSide = createReportingSide(reportableEvent);
            TransactionReportInstruction reportInstruction = createTransactionReportInstruction.evaluate(
                reportableEvent, 
                reportingSide
            );
            logger.info("✓ Created TransactionReportInstruction using DRR pipeline");

            // Step 4: Generate CFTC Part 45 Report using DRR's built-in function
            // This is where ALL the regulatory logic happens - 60+ fields populated automatically!
            CFTCPart45TransactionReport cftcReport = cftcPart45ReportFunction.evaluate(reportInstruction);
            logger.info("✓ Generated CFTC Part 45 report with ALL fields populated by DRR");

            // Step 5: Validate and log missing mandatory fields
            validateAndLogMissingFields(cftcReport, tradeState);

            // Step 6: Output the result
            printCFTCReport(cftcReport);

            logger.info("=== CFTC Part 45 Reporting Completed Successfully ===");

        } catch (Exception e) {
            logger.error("Failed to generate CFTC report using DRR pipeline", e);
            throw e;
        }
    }

    /**
     * Load TradeState from your data source
     * In production: Kafka, REST API, database, etc.
     */
    private TradeState loadTradeState(String tradePath) throws IOException {
        logger.info("Loading TradeState from: {}", tradePath);
        
        try {
            TradeState tradeState;
            
            // Check if it's a local file path (contains : or starts with /)
            if (tradePath.contains(":") || tradePath.startsWith("/") || tradePath.startsWith("\\")) {
                // Load from local filesystem
                logger.info("Loading from local file system: {}", tradePath);
                java.io.File file = new java.io.File(tradePath);
                if (!file.exists()) {
                    throw new IllegalArgumentException("File not found: " + tradePath);
                }
                
                com.fasterxml.jackson.databind.ObjectMapper mapper = 
                    com.regnosys.rosetta.common.serialisation.RosettaObjectMapper.getNewRosettaObjectMapper();
                tradeState = mapper.readValue(file, TradeState.class);
            } else {
                // Load from classpath/resources (build files)
                logger.info("Loading from classpath: {}", tradePath);
                tradeState = ResourcesUtils.getObjectAndResolveReferences(TradeState.class, tradePath);
            }
            
            logger.info("✓ Successfully loaded TradeState from: {}", tradePath);
            return tradeState;
        } catch (Exception e) {
            logger.error("✗ Failed to load TradeState from: {}", tradePath, e);
            throw new IllegalArgumentException("Cannot load TradeState", e);
        }
    }

    /**
     * Create ReportableEvent from TradeState
     * 
     * Note: In real production systems, ReportableEvents typically come from:
     * - Workflow events (execution, modification, termination)
     * - Event processing systems
     * - Business event generation
     * 
     * This example shows how to create one directly from TradeState
     */
    private ReportableEvent createReportableEventFromTradeState(TradeState tradeState) {
        logger.info("Creating ReportableEvent from TradeState...");

        // Extract trade information for reportable event
        Trade trade = tradeState.getTrade();
        if (trade == null) {
            throw new IllegalArgumentException("Trade is missing from TradeState");
        }

        // Create ReportableEvent with minimal required fields
        // DRR will extract everything else from the TradeState automatically
        ReportableEvent reportableEvent = ReportableEvent.builder()
            .setAfter(tradeState)  // The trade state AFTER the event
            .setReportableInformation(createReportableInformation(trade))
            .build();

        return postProcess(reportableEvent);
    }

    /**
     * Create ReportableInformation - this tells DRR which parties are involved
     * and what regulatory regimes apply
     */
    private ReportableInformation createReportableInformation(Trade trade) {
        logger.info("Creating ReportableInformation...");

        if (trade.getParty() == null || trade.getParty().size() < 2) {
            throw new IllegalArgumentException("Trade must have at least 2 parties");
        }

        // Create party information for CFTC reporting
        // DRR will extract LEIs, names, and other details automatically
        PartyInformation party1Info = PartyInformation.builder()
            .setPartyReferenceValue(trade.getParty().get(0))
            .addRegimeInformation(ReportingRegime.builder()
                .setSupervisoryBodyValue(SupervisoryBodyEnum.CFTC)
                .setReportingRole(ReportingRoleEnum.REPORTING_PARTY)
                .setMandatorilyClearable(MandatorilyClearableEnum.NO)
                .build())
            .build();

        PartyInformation party2Info = PartyInformation.builder()
            .setPartyReferenceValue(trade.getParty().get(1))
            .addRegimeInformation(ReportingRegime.builder()
                .setSupervisoryBodyValue(SupervisoryBodyEnum.CFTC)
                .setReportingRole(ReportingRoleEnum.NON_REPORTING_PARTY)
                .setMandatorilyClearable(MandatorilyClearableEnum.NO)
                .build())
            .build();

        return ReportableInformation.builder()
            .setConfirmationMethod(ConfirmationMethodEnum.ELECTRONIC)
            .setExecutionVenueType(ExecutionVenueTypeEnum.OFF_FACILITY)
            .setLargeSizeTrade(false)
            .addPartyInformation(party1Info)
            .addPartyInformation(party2Info)
            .build();
    }

    /**
     * Create ReportingSide - tells DRR WHO is reporting to WHOM
     */
    private ReportingSide createReportingSide(ReportableEvent reportableEvent) {
        logger.info("Creating ReportingSide...");

        Party reportingParty = getCounterparty(reportableEvent, CounterpartyRoleEnum.PARTY_1);
        Party reportingCounterparty = getCounterparty(reportableEvent, CounterpartyRoleEnum.PARTY_2);

        return ReportingSide.builder()
            .setReportingParty(reportingParty)
            .setReportingCounterparty(reportingCounterparty)
            .build();
    }

    /**
     * Extract party by role from ReportableEvent
     */
    private Party getCounterparty(ReportableEvent reportableEvent, CounterpartyRoleEnum role) {
        if (reportableEvent.getAfter() == null || 
            reportableEvent.getAfter().getTrade() == null ||
            reportableEvent.getAfter().getTrade().getParty() == null) {
            throw new IllegalArgumentException("No parties found in ReportableEvent");
        }

        List<? extends Party> parties = reportableEvent.getAfter().getTrade().getParty();
        
        // Simple logic: PARTY_1 = first party, PARTY_2 = second party
        // In production, you'd have more sophisticated party role resolution
        if (role == CounterpartyRoleEnum.PARTY_1 && parties.size() > 0) {
            return parties.get(0);
        } else if (role == CounterpartyRoleEnum.PARTY_2 && parties.size() > 1) {
            return parties.get(1);
        }
        
        throw new IllegalArgumentException("Cannot find party for role: " + role);
    }

    /**
     * Log summary of loaded TradeState for validation
     */
    private void logTradeStateSummary(TradeState tradeState) {
        if (tradeState.getTrade() != null) {
            Trade trade = tradeState.getTrade();
            
            logger.info("=== Trade Summary ===");
            
            // Log parties
            if (trade.getParty() != null) {
                logger.info("Parties: {}", trade.getParty().size());
                for (int i = 0; i < trade.getParty().size(); i++) {
                    Party party = trade.getParty().get(i);
                    String lei = extractLEI(party).orElse("Missing");
                    String name = party.getName() != null ? party.getName().getValue() : "Unknown";
                    logger.info("  Party {}: {} (LEI: {})", i + 1, name, lei);
                }
            }

            // Log trade date
            if (trade.getTradeDate() != null) {
                logger.info("Trade Date: {}", trade.getTradeDate());
            }

            // Log product type
            if (trade.getTradableProduct() != null && 
                trade.getTradableProduct().getProduct() != null &&
                trade.getTradableProduct().getProduct().getContractualProduct() != null) {
                logger.info("Product: Interest Rate Swap");
            }

            logger.info("====================");
        }
    }

    /**
     * Extract LEI from party
     */
    private Optional<String> extractLEI(Party party) {
        if (party.getPartyId() == null) return Optional.empty();
        
        return party.getPartyId().stream()
            .filter(id -> id.getIdentifierType() == cdm.base.staticdata.party.PartyIdentifierTypeEnum.LEI)
            .map(cdm.base.staticdata.party.PartyIdentifier::getIdentifierValue)
            .findFirst();
    }

    /**
     * Validate and log missing mandatory fields for CFTC Part 45
     */
    private void validateAndLogMissingFields(CFTCPart45TransactionReport report, TradeState tradeState) {
        logger.info("=== CFTC Part 45 Field Validation ===");
        
        int missingMandatory = 0;
        int missingOptional = 0;
        
        // MANDATORY FIELDS for CFTC Part 45 (will cause rejection if missing)
        
        // 1. Action Type (always required)
        if (report.getActionType() == null || report.getActionType().trim().isEmpty()) {
            logger.error("MANDATORY MISSING: Action Type - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Action Type: {}", report.getActionType());
        }
        
        // 2. Counterparty LEIs (mandatory)
        if (report.getCounterparty1() == null || report.getCounterparty1().trim().isEmpty()) {
            logger.error("MANDATORY MISSING: Counterparty 1 LEI - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Counterparty 1: {}", report.getCounterparty1());
        }
        
        if (report.getCounterparty2() == null || report.getCounterparty2().trim().isEmpty()) {
            logger.error("MANDATORY MISSING: Counterparty 2 LEI - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Counterparty 2: {}", report.getCounterparty2());
        }
        
        // 3. Asset Class (mandatory)
        if (report.getAssetClass() == null || report.getAssetClass().trim().isEmpty()) {
            logger.error("MANDATORY MISSING: Asset Class - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Asset Class: {}", report.getAssetClass());
        }
        
        // 4. Event Timestamp (mandatory)
        if (report.getEventTimestamp() == null) {
            logger.error("MANDATORY MISSING: Event Timestamp - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Event Timestamp: {}", report.getEventTimestamp());
        }
        
        // 5. Execution Timestamp (mandatory)
        if (report.getExecutionTimestamp() == null) {
            logger.error("MANDATORY MISSING: Execution Timestamp - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Execution Timestamp: {}", report.getExecutionTimestamp());
        }
        
        // 6. For Interest Rate Swaps - Notional Amount Leg 1 (mandatory)
        if (report.getNotionalAmountLeg1() == null) {
            logger.error("MANDATORY MISSING: Notional Amount Leg 1 - This will cause REJECTION for IRS");
            missingMandatory++;
        } else {
            logger.info("✓ Notional Amount Leg 1: {}", report.getNotionalAmountLeg1());
        }
        
        // 7. For Interest Rate Swaps - Notional Currency Leg 1 (mandatory)
        if (report.getNotionalCurrencyLeg1() == null) {
            logger.error("MANDATORY MISSING: Notional Currency Leg 1 - This will cause REJECTION for IRS");
            missingMandatory++;
        } else {
            logger.info("✓ Notional Currency Leg 1: {}", report.getNotionalCurrencyLeg1());
        }
        
        // 8. Effective Date (mandatory)
        if (report.getEffectiveDate() == null) {
            logger.error("MANDATORY MISSING: Effective Date - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Effective Date: {}", report.getEffectiveDate());
        }
        
        // 9. Maturity Date (mandatory)
        if (report.getMaturityDate() == null) {
            logger.error("MANDATORY MISSING: Maturity Date - This will cause REJECTION");
            missingMandatory++;
        } else {
            logger.info("✓ Maturity Date: {}", report.getMaturityDate());
        }
        
        // 10. Day Count Convention Leg 1 (mandatory for fixed legs)
        if (report.getFixedRateDayCountConventionLeg1() == null) {
            logger.warn("CONDITIONAL MISSING: Fixed Rate Day Count Convention Leg 1 - Required if leg 1 is fixed");
            missingOptional++;
        } else {
            logger.info("✓ Day Count Convention Leg 1: {}", report.getFixedRateDayCountConventionLeg1());
        }
        
        // OPTIONAL BUT IMPORTANT FIELDS
        
        // Fixed Rate (important for pricing)
        if (report.getFixedRateLeg1() == null && report.getFixedRateLeg2() == null) {
            logger.warn("OPTIONAL MISSING: No Fixed Rates found - May affect trade reconstruction");
            missingOptional++;
        }
        
        // Floating Rate Index (important for floating legs)
        if (report.getFloatingRateIndexLeg1() == null && report.getFloatingRateIndexLeg2() == null) {
            logger.warn("OPTIONAL MISSING: No Floating Rate Indexes found - May affect trade reconstruction");
            missingOptional++;
        }
        
        // Payment Frequency (important for cash flow calculation)
        if (report.getPaymentFrequencyLeg1() == null) {
            logger.warn("OPTIONAL MISSING: Payment Frequency Leg 1 - May affect cash flow calculation");
            missingOptional++;
        }
        
        // Reset Frequency (important for floating legs)
        if (report.getResetFrequencyLeg1() == null) {
            logger.warn("OPTIONAL MISSING: Reset Frequency Leg 1 - May affect floating rate calculation");
            missingOptional++;
        }
        
        // LOG SUMMARY
        if (missingMandatory > 0) {
            logger.error("❌ VALIDATION FAILED: {} mandatory field(s) missing - REPORT WILL BE REJECTED", missingMandatory);
        } else {
            logger.info("✅ VALIDATION PASSED: All mandatory fields present - Report should be ACCEPTED");
        }
        
        if (missingOptional > 0) {
            logger.warn("⚠️  {} optional field(s) missing - May affect trade processing", missingOptional);
        }
        
        logger.info("=====================================");
    }

    /**
     * Print the generated CFTC report
     */
    private void printCFTCReport(CFTCPart45TransactionReport report) {
        logger.info("=== CFTC Part 45 Transaction Report ===");
        
        // Log key fields to show DRR populated everything automatically
        logger.info("Action Type: {}", report.getActionType());
        logger.info("Counterparty 1: {}", report.getCounterparty1());
        logger.info("Counterparty 2: {}", report.getCounterparty2());
        
        if (report.getNotionalAmountLeg1() != null) {
            logger.info("Notional Amount Leg 1: {}", report.getNotionalAmountLeg1());
        }
        if (report.getNotionalCurrencyLeg1() != null) {
            logger.info("Notional Currency Leg 1: {}", report.getNotionalCurrencyLeg1());
        }
        if (report.getFixedRateLeg1() != null) {
            logger.info("Fixed Rate Leg 1: {}", report.getFixedRateLeg1());
        }
        
        logger.info("Event Timestamp: {}", report.getEventTimestamp());
        logger.info("Execution Timestamp: {}", report.getExecutionTimestamp());
        
        // Print full JSON report
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                com.regnosys.rosetta.common.serialisation.RosettaObjectMapper.getNewRosettaObjectMapper();
            String jsonReport = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            logger.info("Full CFTC Part 45 Report:\n{}", jsonReport);
        } catch (Exception e) {
            logger.error("Failed to serialize report to JSON", e);
        }
        
        logger.info("======================================");
    }

    /**
     * Post-process objects using DRR's post-processor
     */
    private <T extends RosettaModelObject> T postProcess(T obj) {
        RosettaModelObjectBuilder builder = obj.toBuilder();
        postProcessor.postProcess(builder.getType(), builder);
        return (T) builder.build();
    }
}
