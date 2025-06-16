package com.regnosys.drr.examples.util;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.regnosys.drr.DrrRuntimeModuleExternalApi;

/**
 * Simple script to validate CDM files for DRR compatibility
 * 
 * Usage: 
 * 1. Update the FILE_PATH constant below to point to your JSON file
 * 2. Run this script to get detailed validation diagnostics
 */
public class ValidateFile {
    
    // TODO: Update this path to your actual input file
    private static final String FILE_PATH = "result-json-files/fpml-5-10/products/rates/USD-Vanilla-swap.json";
    
    public static void main(String[] args) {
        // Initialize Guice for dependency injection (required for DRR components)
        Injector injector = Guice.createInjector(new DrrRuntimeModuleExternalApi());
        
        String filePath = FILE_PATH;
        
        // Allow override from command line
        if (args.length > 0) {
            filePath = args[0];
        }
        
        System.out.println("=== File Validation for DRR ===");
        System.out.println("Target file: " + filePath);
        System.out.println();
        
        // Run the validation
        CDMStructureValidator validator = new CDMStructureValidator();
        validator.validateFile(filePath);
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("If you found issues, update your file or use a different CDM type for loading.");
        System.out.println("For working examples, check files in:");
        System.out.println("- result-json-files/fpml-5-10/record-keeping/products/rates/");
        System.out.println("- result-json-files/fpml-5-10/record-keeping/events/");
    }
} 
