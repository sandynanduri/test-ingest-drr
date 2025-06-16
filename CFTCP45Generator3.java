package com.regnosys.drr.examples;

import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.base.staticdata.party.PartyIdentifierTypeEnum;
import com.google.common.io.Resources;
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
import com.rosetta.model.metafields.FieldWithMetaString;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class CFTCP45Generator {

    public static void main(String[] args) throws IOException {
        String filePath = "regulatory-reporting/input/events/InterestRateSwap-NEWT-01.json";
        
        // Check if file exists
        try {
            URL url = Resources.getResource(filePath);
            String json = Resources.toString(url, StandardCharsets.UTF_8);
            System.out.println("=== Found JSON File ===");
            System.out.println("File path: " + filePath);
            System.out.println("File content length: " + json.length() + " characters");
            System.out.println("First 200 characters: " + json.substring(0, Math.min(200, json.length())));
            System.out.println("=========================");
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: Could not find file: " + filePath);
            System.err.println("Please ensure the file exists in the classpath at: " + filePath);
            return;
        }

        // 1. Deserialise a ReportableEvent JSON from the test pack
        try {
            ReportableEvent reportableEvent = ResourcesUtils.getObjectAndResolveReferences(ReportableEvent.class, filePath);

            // Print the ReportableEvent
            System.out.println("\n=== ReportableEvent Details ===");
            System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(reportableEvent));
            System.out.println("=============================");

            // Run report
            CFTCP45Generator generator = new CFTCP45Generator();
            generator.runReport(reportableEvent);
        } catch (Exception e) {
            System.err.println("ERROR: Failed to process ReportableEvent");
            System.err.println("Error message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private final Injector injector;

    CFTCP45Generator() {
        this.injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
    }

    void runReport(ReportableEvent reportableEvent) throws IOException {
        // Create hardcoded reporting side
        final ReportingSide reportingSide = ReportingSide.builder()
                .setReportingParty(createHardcodedParty("PARTY1", "LEI1"))
                .setReportingCounterparty(createHardcodedParty("PARTY2", "LEI2"))
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

    private ReferenceWithMetaParty createHardcodedParty(String name, String lei) {
        return ReferenceWithMetaParty.builder()
                .setValue(Party.builder()
                        .setName(FieldWithMetaString.builder()
                                .setValue(name)
                                .build())
                        .addPartyId(PartyIdentifier.builder()
                                .setIdentifierType(PartyIdentifierTypeEnum.LEI)
                                .setIdentifierValue(lei)
                                .build())
                        .build())
                .build();
    }
} 
