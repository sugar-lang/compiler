package org.sugarj.driver.transformations.primitive;

import org.spoofax.interpreter.library.AbstractStrategoOperatorRegistry;
import org.sugarj.driver.Driver;
import org.sugarj.driver.ImportCommands;

/**
 * Provides Stratego primitives for SugarJ.
 * 
 * @author seba
 */
public class SugarJPrimitivesLibrary extends AbstractStrategoOperatorRegistry {

  public static final String REGISTRY_NAME = "SUGARJ";
  
  public SugarJPrimitivesLibrary(Driver driver, ImportCommands importCommands) {
    add(new ATermToString());
//    add(new CurrentPackage(driver));
//    add(new CurrentModule(driver));
//    add(new CurrentImports(driver));
//    add(new CurrentRenamings(driver.getParameters()));
    add(new ResolveModelImport(importCommands, driver.getParameters().env));
    add(new CurrentTransformationName(importCommands));
//    add(new CompileTransformed(driver, environment));
//    add(new WriteTransformed(driver, environment));
  }
  
  @Override
  public String getOperatorRegistryName() {
    return REGISTRY_NAME;
  }
}
