package com.regnosys.drr.examples;

import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.base.staticdata.party.PartyIdentifierTypeEnum;
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

public class CFTCP45Generator {

    public static void main(String[] args) throws IOException {
        // 1. Deserialise a ReportableEvent JSON from the test pack
        ReportableEvent reportableEvent = ResourcesUtils.getObjectAndResolveReferences(ReportableEvent.class, "regulatory-reporting/input/events/InterestRateSwap-NEWT-01.json");

        // Print the ReportableEvent
        System.out.println("=== ReportableEvent Details ===");
        System.out.println(RosettaObjectMapper.getNewRosettaObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(reportableEvent));
        System.out.println("=============================");

        // Run report
        CFTCP45Generator generator = new CFTCP45Generator();
        generator.runReport(reportableEvent);
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
