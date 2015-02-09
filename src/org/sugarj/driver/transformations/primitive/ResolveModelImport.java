package org.sugarj.driver.transformations.primitive;

import java.io.IOException;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Environment;
import org.sugarj.driver.ImportCommands;
import org.sugarj.driver.ModuleSystemCommands;

/**
 * Primitive for looking up and loading a model according to the current environment.
 * If successful, this primitive returns the loaded model as a term.
 * 
 * @author seba
 */
class ResolveModelImport extends AbstractPrimitive {

  private ImportCommands imp;
  private Environment environment;
  
  public ResolveModelImport(ImportCommands imp, Environment environment) {
    super("SUGARJ_resolve_model_import", 0, 0);
    this.imp = imp;
    this.environment = environment;
  }

  @Override
  public boolean call(IContext context, Strategy[] svars, IStrategoTerm[] tvars) throws InterpreterException {
    IStrategoTerm importTerm = context.current();
    
    String modulePath = imp.computeModulePathOfImport(importTerm);

    if (modulePath == null) {
      org.strategoxt.imp.runtime.Environment.logException("Look up of module failed, not an import declaration: " + importTerm);
      return false;
    }
    
    RelativePath importModelPath = ModuleSystemCommands.importModel(modulePath, environment, null);
    if (importModelPath == null) {
//      driver.setErrorMessage("Warning: Look up of module failed, cannot locate model: " + resolve.a);
      return false;
    }
    
    IStrategoTerm model;
    try {
      model = ATermCommands.atermFromFile(importModelPath.getAbsolutePath());
    } catch (IOException e) {
      throw new InterpreterException(e);
    }  

    context.setCurrent(model);
    return true;
  }
  
}
 