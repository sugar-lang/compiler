package org.sugarj.driver.transformations.primitive;

import java.io.IOException;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Driver;
import org.sugarj.driver.Environment;
import org.sugarj.driver.Renaming;
import org.sugarj.driver.Renaming.FromTo;

/**
 * Primitive for looking up and loading a model according to the current environment.
 * If successful, this primitive returns the loaded model as a term.
 * 
 * @author seba
 */
class WriteTransformed extends AbstractPrimitive {

  private Driver driver;
  private Environment environment;
  
  public WriteTransformed(Driver driver, Environment environment) {
    super("SUGARJ_write", 0, 2);
    this.driver = driver;
    this.environment = environment;
  }

  @Override
  public boolean call(IContext context, Strategy[] svars, IStrategoTerm[] tvars) throws InterpreterException {
    IStrategoTerm generatedModel = context.current();
    
    String modelPath = ATermCommands.getString(tvars[0]);
    RelativePath modelRelativePath = new RelativePath(modelPath);
    
    IStrategoTerm transformationsTerm = tvars[1];
    RelativePath transformationPath = new RelativePath(ATermCommands.getString(transformationsTerm)); 
    
    RelativePath source = Renaming.getTransformedModelSourceFilePath(modelRelativePath, transformationPath, environment);
    
    try {
      FromTo ren = new FromTo(modelPath, source.getRelativePath());
      driver.getParameters().renamings.add(0, ren);
//      generatedModel = driver.currentRename(generatedModel);

      driver.generateFile(source, ATermCommands.atermToString(generatedModel));
    } catch (IOException e) {
      driver.setErrorMessage(e.getLocalizedMessage());
    }
    return true;
  }
  
}