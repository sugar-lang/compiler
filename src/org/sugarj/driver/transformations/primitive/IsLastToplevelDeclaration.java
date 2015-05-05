package org.sugarj.driver.transformations.primitive;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.driver.Driver;

/**
 * Primitive to check if the toplevel declaration currently being processed is
 * the last one.
 * 
 * Required by SoundX. Strategy succeeds if the current declaration is the last
 * one, fails otherwise.
 * 
 * @author Florian Lorenzen <florian.lorenzen@tu-berlin.de>
 */
public class IsLastToplevelDeclaration extends AbstractPrimitive {
  private Driver driver;

  public IsLastToplevelDeclaration(Driver driver) {
    super("SUGARJ_is_last_toplevel_declaration", 0, 0);
    this.driver = driver;
  }

  @Override
  public boolean call(IContext arg0, Strategy[] arg1, IStrategoTerm[] arg2) throws InterpreterException {
    return !driver.getParameters().declProvider.hasNextToplevelDecl();
  }
}
