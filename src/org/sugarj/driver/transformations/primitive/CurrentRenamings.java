package org.sugarj.driver.transformations.primitive;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.driver.DriverInput;
import org.sugarj.driver.Renaming;

/**
 * Primitive for retrieving the current renamings set in the driver's environment.
 * 
 * @author seba
 */
class CurrentRenamings extends AbstractPrimitive {

  private DriverInput params;
  
  public CurrentRenamings(DriverInput params) {
    super("SUGARJ_current_renamings", 0, 0);
    this.params = params;
  }

  @Override
  public boolean call(IContext context, Strategy[] svars, IStrategoTerm[] tvars) throws InterpreterException {
    IStrategoTerm map = Renaming.makeRenamingHashtable(params.renamings);
    context.setCurrent(map);
    return true;
  }
  
}