package org.sugarj.driver.transformations.primitive;

import org.spoofax.interpreter.core.IContext;
// import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.compat.CompatLibrary;
import org.strategoxt.tools.ppgenerate_0_0;
import org.sugarj.driver.Driver;
import org.strategoxt.tools.sdf_desugar_0_0;


/**
 * Primitive to generate a pretty printer table from an SDF definition.
 * 
 * @author Florian Lorenzen <florian.lorenzen@tu-berlin.de>
 */
public class GeneratePPTable extends AbstractPrimitive {
  public GeneratePPTable(Driver driver) {
    super("SUGARJ_generate_pp_table", 0, 0);
  }

  @Override
  public boolean call(IContext ctx, Strategy[] arg1, IStrategoTerm[] arg2) {
    IStrategoTerm term = ctx.current();
    
    //System.out.println("GEN PP HAS BEEN CALLED");
    //System.out.println("THE CURRENT TERM IS " + term);

    Context cctx = HybridInterpreter.getCompiledContext(ctx);
    cctx.addOperatorRegistry(new CompatLibrary());
    try {
      IStrategoTerm fixedSdf = sdf_desugar_0_0.instance.invoke(cctx, term);
      IStrategoTerm result = ppgenerate_0_0.instance.invoke(cctx, fixedSdf);
      //System.out.println("RESULT IS " + result);
      ctx.setCurrent(result);
    } catch (Exception e) {
      System.out.println("CAUGHT EXCEPTION " + e);
      return false;
    }
    return true;
  }
}
