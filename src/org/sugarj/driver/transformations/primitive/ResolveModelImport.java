package org.sugarj.driver.transformations.primitive;

import java.io.IOException;
import java.text.ParseException;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.shared.SGLRException;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Driver;
import org.sugarj.driver.ModuleSystemCommands;
import org.sugarj.util.Pair;

/**
 * Primitive for looking up and loading a model according to the current environment.
 * If successful, this primitive returns the loaded model as a term.
 * 
 * @author seba
 */
class ResolveModelImport extends AbstractPrimitive {

  private Driver driver;
  
  public ResolveModelImport(Driver driver) {
    super("SUGARJ_resolve_model_import", 0, 0);
    this.driver = driver;
  }

  @Override
  public boolean call(IContext context, Strategy[] svars, IStrategoTerm[] tvars) throws InterpreterException {
    IStrategoTerm importTerm = context.current();
    if (!driver.getBaseLanguage().getLanguage().isImportDecl(importTerm)) {
      driver.setErrorMessage("Look up of module failed, not an import declaration: " + importTerm);
      return false;
    }
    
    Pair<String, Boolean> resolve;
    try {
      resolve = driver.resolveImportDec(importTerm);
    } catch (ClassNotFoundException | IOException | ParseException | InvalidParseTableException | SGLRException | InterruptedException e) {
      throw new InterpreterException(e);
    }
    if (resolve == null || resolve.a == null) {
      driver.setErrorMessage("Warning: Look up of module failed, could not resolve import: " + importTerm);
      return false;
    }
    
    RelativePath importModelPath = ModuleSystemCommands.importModel(resolve.a, driver.getParameters().env, driver.getCurrentResult());
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