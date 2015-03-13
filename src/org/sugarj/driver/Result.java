package org.sugarj.driver;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.shared.BadTokenException;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Result implements Serializable, Externalizable {

  public static final long serialVersionUID = 2546270233774434268L;

  protected List<IStrategoTerm> editorServices;
  protected List<String> collectedErrors;
  protected Set<BadTokenException> parseErrors;
  protected IStrategoTerm sugaredSyntaxTree;
  protected IStrategoTerm desugaredSyntaxTree;
  protected Path parseTableFile;
  protected Path desugaringsFile;
  
  /**
   * maps from source artifacts to generated source files 
   */
  private Map<Set<? extends Path>, Set<? extends Path>> deferredSourceFiles;
  
  public Result() { 
    editorServices = new LinkedList<IStrategoTerm>();
    collectedErrors = new LinkedList<String>();
    parseErrors = new HashSet<BadTokenException>();
    sugaredSyntaxTree = null;
    desugaredSyntaxTree = null;
    parseTableFile = null;
    desugaringsFile = null;
    deferredSourceFiles = new HashMap<>();
 }
  
  public void addEditorService(IStrategoTerm service) {
    if (!editorServices.contains(service))
      editorServices.add(service);
  }
  
  public List<IStrategoTerm> getEditorServices() {
    if (desugaringsFile != null && FileCommands.exists(desugaringsFile))
      return ATermCommands.registerSemanticProvider(editorServices, desugaringsFile);
    return editorServices;
  }
  
  // TODO encode via OutputStamper
  public boolean isConsistent() {
    if (desugaringsFile != null && !FileCommands.exists(desugaringsFile))
      return false;
    
    return true;
  }
  
  public void logError(String error) {
    collectedErrors.add(error);
  }
  
  public List<String> getCollectedErrors() {
    return collectedErrors;
  }
  
  public void logParseError(BadTokenException e) {
    parseErrors.add(e);  
  }
  
  public Set<BadTokenException> getParseErrors() {
    return parseErrors;
  }
  
  public void setSugaredSyntaxTree(IStrategoTerm sugaredSyntaxTree) {
    this.sugaredSyntaxTree = sugaredSyntaxTree;
  }
  
  public IStrategoTerm getSugaredSyntaxTree() {
    return sugaredSyntaxTree;
  }
  
  public void setDesugaredSyntaxTree(IStrategoTerm desugaredSyntaxTree) {
    this.desugaredSyntaxTree = desugaredSyntaxTree;
  }
  
  public IStrategoTerm getDesugaredSyntaxTree() {
    return desugaredSyntaxTree;
  }
  
  void delegateCompilation(Result delegate, Set<? extends Path> sourceFiles, Set<? extends Path> compileFiles) {
    delegate.deferredSourceFiles.putAll(deferredSourceFiles);
    delegate.deferredSourceFiles.put(sourceFiles, compileFiles);
  }
  
  boolean isDelegateOf(Set<? extends Path> sourceFiles) {
    for (Set<? extends Path> deferred : deferredSourceFiles.keySet())
      if (!Collections.disjoint(deferred, sourceFiles))
        return true;
    return false;
  }
  
  void resetDelegation() {
    deferredSourceFiles.clear();
  }
  
  public void registerParseTable(Path tbl) {
    this.parseTableFile = tbl;
  }
  
  public Path getParseTable() {
    return parseTableFile;
  }
  
  public void registerEditorDesugarings(Path jarfile) {
//    addEditorService(
//        ATermCommands.atermFromString(
//          "Builders(\"sugarj checking\", [SemanticObserver(Strategy(\"sugarj-analyze\"))])"));
    
    desugaringsFile = jarfile;
  }
  
  public Path getDesugaringsFile() {
    return desugaringsFile;
  }
  
  public Set<Path> getDeferredSourceFiles() {
    Set<Path> res = new HashSet<>();
    for (Set<? extends Path> s : deferredSourceFiles.values())
      res.addAll(s);
    return res;
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    List<String> errors = new ArrayList<String>(collectedErrors);
    for (BadTokenException e : parseErrors)
      errors.add("syntax error: line " + e.getLineNumber() + " column " + e.getColumnNumber() + ": " + e.getMessage());
    out.writeObject(errors);
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    this.collectedErrors = (List<String>) in.readObject();
  }
}
