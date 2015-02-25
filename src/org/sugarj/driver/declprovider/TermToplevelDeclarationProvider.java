package org.sugarj.driver.declprovider;

import java.util.ArrayList;
import java.util.List;

import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.imploder.IToken;
import org.spoofax.jsglr.client.imploder.ImploderAttachment;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.path.Path;
import org.sugarj.driver.Driver;
import org.sugarj.driver.Environment;

/**
 * @author jp
 * @author seba
 */
public class TermToplevelDeclarationProvider implements ToplevelDeclarationProvider {
  private static final long serialVersionUID = -7078184145952172284L;
  
  private List<IStrategoTerm> terms;
  int index;
  
  public TermToplevelDeclarationProvider(IStrategoTerm source, Path sourceFile, Environment env) {
    if (source.getTermType() == IStrategoTerm.TUPLE)
      source = source.getSubterm(0);
    
    if (!ATermCommands.isApplication(source, "CompilationUnit") || 
        source.getSubtermCount() != 1 || 
        source.getSubterm(0).getTermType() != IStrategoTerm.LIST)
      throw new IllegalArgumentException("Ill-formed input term.");
    
    IStrategoList decls = (IStrategoList) ATermCommands.getApplicationSubterm(source, "CompilationUnit", 0);
    
    index = 0;
    terms = new ArrayList<IStrategoTerm>();
    
    for (IStrategoTerm t : decls)
      terms.add(t);
  }
  
  @Override
  public IStrategoTerm getNextToplevelDecl(boolean recovery, boolean lookahead) {
    return terms.get(index++);
  }
  
  @Override
  public boolean hasNextToplevelDecl() {
    return index < terms.size();
  }

  @Override
  public void retract(IStrategoTerm term) {
    if (index <= 0)
      throw new IllegalStateException();
    
    if (terms.get(index - 1).equals(term))
      index--;
    else
      throw new IllegalArgumentException();
  }

  @Override
  public IToken getStartToken() {
    if (!terms.isEmpty())
      return ImploderAttachment.getLeftToken(terms.get(0));
    return null;
  }

  @Override
  public void setDriver(Driver driver) {
    // this decl provider does not need the driver
  }
  
}