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
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.TargettedMode;
import org.sugarj.common.cleardep.CompilationUnit;
import org.sugarj.common.cleardep.Mode;
import org.sugarj.common.cleardep.Stamper;
import org.sugarj.common.cleardep.Synthesizer;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

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
  transient private Map<Path, Integer> transitivelyAffectedFiles;

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
  public void addExternalFileDependency(Path file, int stampOfFile) {
    super.addExternalFileDependency(file, stampOfFile);
    getTransitivelyAffectedFileStamps().put(file, stampOfFile);
  }
  
  @Override
  public void addGeneratedFile(Path file, int stampOfFile) {
    super.addGeneratedFile(file, stampOfFile);
    getTransitivelyAffectedFileStamps().put(file, stampOfFile);
  }
  
  @Override
  public void addModuleDependency(CompilationUnit mod) {
    super.addModuleDependency(mod);
    if (mod instanceof Result)
      getTransitivelyAffectedFileStamps().putAll(((Result) mod).getTransitivelyAffectedFileStamps());
  }

  private Map<Path, Integer> getTransitivelyAffectedFileStamps() {
    if (transitivelyAffectedFiles == null) {
      final Map<Path, Integer> deps = new HashMap<>();
      
      ModuleVisitor<Void> collectAffectedFileStampsVisitor = new ModuleVisitor<Void>() {
        @Override public Void visit(CompilationUnit mod, Mode<?> mode) {
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
  
  public Path getPersistentPath() {
    return persistentPath;
  }
  
  public boolean isGenerated() {
    for (RelativePath sourceFile : getSourceArtifacts())
      if (sourceFile != null && "model".equals(FileCommands.getExtension(sourceFile)))
        return true;
    return false;
  }

  public Set<Path> getDeferredSourceFiles() {
    Set<Path> res = new HashSet<>();
    for (Set<? extends Path> s : deferredSourceFiles.values())
      res.addAll(s);
    return res;
  }


  @Override
  protected void writeEntity(ObjectOutputStream oos) throws IOException {
    super.writeEntity(oos);
    oos.writeObject(mode);
//    oos.writeObject(editorServices = Collections.unmodifiableList(editorServices));
//    oos.writeObject(collectedErrors = Collections.unmodifiableList(collectedErrors));
//    oos.writeObject(parseErrors = Collections.unmodifiableSet(parseErrors));
//    oos.writeObject(sugaredSyntaxTree);
//    oos.writeObject(AnalysisDataAttachment.getAnalysisData(sugaredSyntaxTree));
//    oos.writeObject(desugaredSyntaxTree);
//    oos.writeObject(parseTableFile);
//    oos.writeObject(desugaringsFile);
//    oos.writeBoolean(failed);
//    oos.writeObject(deferredSourceFiles);
//    transitivelyAffectedFiles = null;
  }
  
  @Override
  protected void readEntity(ObjectInputStream ois) throws IOException, ClassNotFoundException {
    super.readEntity(ois);
    mode = (Mode<?>) ois.readObject();
    transitivelyAffectedFiles = null;
//    editorServices = (List<IStrategoTerm>) ois.readObject();
//    collectedErrors = (List<String>) ois.readObject();
//    parseErrors = (Set<BadTokenException>) ois.readObject();
//    sugaredSyntaxTree = (IStrategoTerm) ois.readObject();
//    Map<TermKey, Map<String, IStrategoTerm>> map = (Map<TermKey, Map<String, IStrategoTerm>>) ois.readObject();
//    if (map != null)
//      sugaredSyntaxTree.putAttachment(new AnalysisDataAttachment(map));
//    desugaredSyntaxTree = (IStrategoTerm) ois.readObject();
//    parseTableFile = (Path) ois.readObject();
//    desugaringsFile = (Path) ois.readObject();
//    failed = ois.readBoolean();
//    deferredSourceFiles = (Map<Set<? extends Path>, Set<? extends Path>>) ois.readObject();
  }

//  @Override
//  protected void copyContentTo(CompilationUnit c) {
//    super.copyContentTo(c);
//    Result compiled = (Result) c;
//    compiled.editorServices.addAll(editorServices);
//    compiled.collectedErrors.addAll(collectedErrors);
//    compiled.parseErrors.addAll(parseErrors);
//    compiled.sugaredSyntaxTree = sugaredSyntaxTree;
//    compiled.desugaredSyntaxTree = desugaredSyntaxTree;
//    compiled.parseTableFile = parseTableFile;
//    compiled.desugaringsFile = desugaringsFile;
//    compiled.transitivelyAffectedFiles = null;
//    
//    for (Entry<Set<? extends Path>, Set<? extends Path>> e : deferredSourceFiles.entrySet()) {
//      Set<RelativePath> newVal = new HashSet<>();
//      for (Path p : e.getValue()) {
//        RelativePath oldP = FileCommands.getRelativePath(targetDir, p);
//        RelativePath newP = new RelativePath(compiled.targetDir, oldP.getRelativePath());
//        newVal.add(newP);
//      }
//      compiled.deferredSourceFiles.put(e.getKey(), newVal);
//    }
//  }
  
  public static Result read(Stamper stamper, Path p) throws ClassNotFoundException, IOException {
    return read(Result.class, stamper, p);
  }
  
  public static Result read(Stamper stamper, Mode<Result> mode, Path... deps) throws IOException {
    return CompilationUnit.read(Result.class, stamper, mode, deps);
  }
  
  public static Result readConsistent(Stamper stamper, Mode<Result> mode, Map<RelativePath, Integer> sourceFiles, Path... deps) throws IOException {
    return CompilationUnit.readConsistent(Result.class, stamper, mode, sourceFiles, deps);
  }

  public static Result create(Stamper stamper, Mode<Result> mode, Synthesizer syn, Map<RelativePath, Integer> sourceFiles, Path dep) throws IOException {
    return CompilationUnit.create(Result.class, stamper, mode, syn, sourceFiles, dep);
  }
  
  public static class CompilerMode extends TargettedMode<Result> {
    private static final long serialVersionUID = -6029285930002846101L;
    
    private Path targetDir;
    private boolean isTemporary;
    
    public CompilerMode() { /* for deserialization */ }
    
    public CompilerMode(Path targetDir, boolean isTemporary) {
      this.targetDir = targetDir;
      this.isTemporary = isTemporary;
      try {
        FileCommands.createDir(targetDir);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    
    @Override
    public Mode<Result> getModeForRequiredModules() {
      return this;
    }

    @Override
    public boolean canAccept(Result e) {
      return e.getMode() == this;
    }

    @Override
    public Result accept(Result e) {
      return e;
    }
    
    @Override
    public Path getTargetDir() {
      return targetDir;
    }

    @Override
    public boolean isTemporary() {
      return isTemporary;
    }
    
    @Override
    public String toString() {
      return "CompilerMode(" + targetDir + ")";
    }
  }
  
  public static class EditorMode extends CompilerMode {
    private static final long serialVersionUID = -8919402178703175221L;
    
    public EditorMode() {
      this(FileCommands.tryNewTempDir(), true);
    }
    
    public EditorMode(Path targetDir, boolean isTemporary) {
      super(targetDir, isTemporary);
    }
    
    @Override
    public Mode<Result> getModeForRequiredModules() {
      return new CompilerMode(getTargetDir(), true);
    }

    @Override
    public boolean canAccept(Result e) {
      return e.getDesugaringsFile() != null && FileCommands.exists(e.getDesugaringsFile());
    }

    @Override
    public Result accept(Result e) {
      return e;
    }
    
    @Override
    public String toString() {
      return "EditorMode(" + getTargetDir() + ")";
    }

  }
}
