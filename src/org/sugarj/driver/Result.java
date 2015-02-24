package org.sugarj.driver;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.shared.BadTokenException;
import org.sugarj.cleardep.CompilationUnit;
import org.sugarj.cleardep.PersistableEntity;
import org.sugarj.cleardep.build.BuildRequirement;
import org.sugarj.cleardep.stamp.Stamp;
import org.sugarj.cleardep.stamp.Stamper;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.Path;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Result extends CompilationUnit {

  public static final long serialVersionUID = 2546270233774434268L;
  
  private List<IStrategoTerm> editorServices;
  private List<String> collectedErrors;
  private Set<BadTokenException> parseErrors;
  private IStrategoTerm sugaredSyntaxTree;
  private IStrategoTerm desugaredSyntaxTree;
  private Path parseTableFile;
  private Path desugaringsFile;
  
  /**
   * Transitive closure (over module dependencies) of required and generated files.
   */
  transient private Map<Path, Stamp> transitivelyAffectedFiles;

  /**
   * maps from source artifacts to generated source files 
   */
  private Map<Set<? extends Path>, Set<? extends Path>> deferredSourceFiles;
  
  public Result() { /* for deserialization only */ }
  
  @Override
  protected void init() {
    super.init();
    editorServices = new LinkedList<IStrategoTerm>();
    collectedErrors = new LinkedList<String>();
    parseErrors = new HashSet<BadTokenException>();
    sugaredSyntaxTree = null;
    desugaredSyntaxTree = null;
    parseTableFile = null;
    desugaringsFile = null;
    transitivelyAffectedFiles = new HashMap<>();
    deferredSourceFiles = new HashMap<>();
  }
  
  @Override
  public void addExternalFileDependency(Path file, Stamp stampOfFile) {
    super.addExternalFileDependency(file, stampOfFile);
    getTransitivelyAffectedFileStamps().put(file, stampOfFile);
  }
  
  @Override
  public void addGeneratedFile(Path file, Stamp stampOfFile) {
    super.addGeneratedFile(file, stampOfFile);
    getTransitivelyAffectedFileStamps().put(file, stampOfFile);
  }
  
  @Override
  public void addModuleDependency(CompilationUnit mod, BuildRequirement<?, ?, ?, ?> req) {
    super.addModuleDependency(mod, req);
    if (mod instanceof Result)
      getTransitivelyAffectedFileStamps().putAll(((Result) mod).getTransitivelyAffectedFileStamps());
  }

  private Map<Path, Stamp> getTransitivelyAffectedFileStamps() {
    if (transitivelyAffectedFiles == null) {
      final Map<Path, Stamp> deps = new HashMap<>();
      
      ModuleVisitor<Void> collectAffectedFileStampsVisitor = new ModuleVisitor<Void>() {
        @Override public Void visit(CompilationUnit mod) {
          deps.putAll(((Result) mod).generatedFiles); 
          deps.putAll(((Result) mod).externalFileDependencies);
          return null;
        }
        @Override public Void combine(Void v1, Void v2) { return null; }
        @Override public Void init() { return null; }
        @Override public boolean cancel(Void t) { return false; }
      };
      
      visit(collectAffectedFileStampsVisitor);
      
      synchronized(this) { transitivelyAffectedFiles = deps; }
    }

    return transitivelyAffectedFiles;
  }

  public Set<Path> getTransitivelyAffectedFiles() {
    return getTransitivelyAffectedFileStamps().keySet();
  }
  

  public void generateFile(Path file, String content) throws IOException {
    if (!FileCommands.exists(file) || !content.equals(FileCommands.readFileAsString(file)))
      FileCommands.writeToFile(file, content);
    addGeneratedFile(file);
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
  
  @Override
  protected boolean isConsistentExtend() {
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
  
  void delegateCompilation(Result delegate, Set<Path> compileFiles,  boolean hasNonBaseDec) {
    delegate.deferredSourceFiles.putAll(deferredSourceFiles);
    delegate.deferredSourceFiles.put(getSourceArtifacts(), compileFiles);
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
  
  public boolean isGenerated() {
    return getGeneratedBy()...;
  }

  public Set<Path> getDeferredSourceFiles() {
    Set<Path> res = new HashSet<>();
    for (Set<? extends Path> s : deferredSourceFiles.values())
      res.addAll(s);
    return res;
  }

  @Override
  protected void readEntity(ObjectInputStream ois, Stamper stamper) throws IOException, ClassNotFoundException {
    super.readEntity(ois, stamper);
    transitivelyAffectedFiles = null;
  }
  
  public static Result read(Path dep) throws IOException {
    return PersistableEntity.read(Result.class, dep);
  }
  
  public static Result readConsistent(Map<? extends Path, Stamp> editedSourceFiles, Path dep, BuildRequirement<?, Result, ?, ?> generatedBy) throws IOException {
    return CompilationUnit.readConsistent(Result.class, editedSourceFiles, dep, generatedBy);
  }
  
  public static Result create(Stamper stamper, Path dep, BuildRequirement<?, Result, ?, ?> generatedBy) throws IOException {
    Result res = CompilationUnit.create(Result.class, stamper, dep, generatedBy);
    return res;
  }
}
