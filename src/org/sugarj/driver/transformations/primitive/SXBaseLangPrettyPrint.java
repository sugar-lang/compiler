package org.sugarj.driver.transformations.primitive;

import java.lang.reflect.Method;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.AbstractBaseProcessor;
import org.sugarj.common.ATermCommands;
import org.sugarj.driver.Driver;

public class SXBaseLangPrettyPrint extends AbstractPrimitive {
  private AbstractBaseProcessor baseProcessor;

  public SXBaseLangPrettyPrint(Driver driver) {
    super("SUGARJ_sx_base_lang_pretty_print", 0, 0);
    baseProcessor = driver.getBaseLanguage();
  }

  @Override
  public boolean call(IContext ctx, Strategy[] arg1, IStrategoTerm[] arg2) throws InterpreterException {
    System.out.println("sx base lang pretty print");
    Class<?> cls = baseProcessor.getClass();
    Method[] methods = cls.getMethods();
    boolean hasPrettyPrint = false;
    int i = 0;
    while (!hasPrettyPrint && i < methods.length) {
      Method m = methods[i];
      if (m.getName().equals("prettyPrint"))
        hasPrettyPrint = true;
      else
        i++;
    }
    if (hasPrettyPrint) {
      System.out.println("found prettyPrint");
      try {
        IStrategoTerm term = ctx.current();
        Object result = methods[i].invoke(baseProcessor, term);
        String text = (String) result;
        System.out.println("pretty print result: " + text);
        ctx.setCurrent(ATermCommands.makeString(text));
        return true;
      } catch (Exception e) {
        return false;
      }
    } else
      return false;
  }
}
