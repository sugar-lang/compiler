package org.sugarj.driver.transformations.primitive;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.stratego_gpp.ast2abox_0_1;
import org.strategoxt.stratego_gpp.box2text_string_0_1;
import org.sugarj.driver.Driver;
import org.sugarj.common.ATermCommands;

/**
 * Primitive to call the pretty printer.
 * 
 * @author Florian Lorenzen <florian.lorenzen@tu-berlin.de>
 *
 */
public class PrettyPrint extends AbstractPrimitive {
  public PrettyPrint(Driver driver) {
    super("SUGARJ_pretty_print", 0, 1);
  }

  @Override
  public boolean call(IContext ctx, Strategy[] arg1, IStrategoTerm[] arg2) throws InterpreterException {
    IStrategoTerm term = ctx.current();
    IStrategoTerm ppTable = arg2[0];
    IStrategoTerm ppt_list = ATermCommands.makeList("PPTable", ppTable);

    try {
      IStrategoTerm aboxTerm = ast2abox_0_1.instance.invoke(HybridInterpreter.getCompiledContext(ctx), term, ppt_list);
      if (aboxTerm == null)
        return false;
      IStrategoTerm textTerm = box2text_string_0_1.instance.invoke(HybridInterpreter.getCompiledContext(ctx), aboxTerm, ATermCommands.factory.makeInt(80));
      ctx.setCurrent(textTerm);
    } catch (Exception e) {
      return false;
    }
    return true;
  }
}
