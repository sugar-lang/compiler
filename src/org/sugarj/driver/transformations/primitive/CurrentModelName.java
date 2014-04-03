package org.sugarj.driver.transformations.primitive;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.common.ATermCommands;
import org.sugarj.driver.ImportCommands;

class CurrentModelName extends AbstractPrimitive {

  private ImportCommands importCommands;
  
  public CurrentModelName(ImportCommands importCommands) {
    super("SUGARJ_current_model_name", 0, 0);
    this.importCommands = importCommands;
  }

  @Override
  public boolean call(IContext context, Strategy[] svars, IStrategoTerm[] tvars) throws InterpreterException {
    String name = importCommands.getCurrentModelName();

    if (name == null)
      return false;

    context.setCurrent(ATermCommands.makeString(name));
    return true;
  }
  
}