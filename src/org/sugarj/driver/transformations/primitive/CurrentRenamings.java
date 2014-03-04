package org.sugarj.driver.transformations.primitive;

import java.util.List;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.common.Environment;
import org.sugarj.common.Renaming;
import org.sugarj.common.Renaming.FromTo;

/**
 * Primitive for retrieving the current renamings set in the driver's environment.
 * 
 * @author seba
 */
class CurrentRenamings extends AbstractPrimitive {

  private Environment environment;
  
  public CurrentRenamings(Environment environment) {
    super("SUGARJ_current_renamings", 0, 0);
    this.environment = environment;
  }

  @Override
  public boolean call(IContext context, Strategy[] svars, IStrategoTerm[] tvars) throws InterpreterException {
    List<FromTo> renamings = environment.getRenamings();
    IStrategoTerm map = Renaming.makeRenamingHashtable(renamings);
    context.setCurrent(map);
    return true;
  }
  
}