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
import org.sugarj.common.cleardep.CompilationUnit;
import org.sugarj.common.cleardep.Stamper;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Result extends CompilationUnit {

  /**
   *  This is a parse result if it was produced during parsing.
   */
  private Path parseResultPath;

  private List<IStrategoTerm> editorServices;
  private List<String> collectedErrors;
  private Set<BadTokenException> parseErrors;
  private IStrategoTerm sugaredSyntaxTree;
  private IStrategoTerm desugaredSyntaxTree;
  private Path parseTableFile;
  private Path desugaringsFile;
  private boolean failed;
  
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
    failed = false;
    transitivelyAffectedFiles = new HashMap<>();
    deferredSourceFiles = new HashMap<>();
  }
  
  public void addExternalFileDependency(RelativePath file, int stampOfFile) {
    super.addExternalFileDependency(file, stampOfFile);
    transitivelyAffectedFiles.put(file, stampOfFile);
  }
  
  public void addGeneratedFile(Path file, int stampOfFile) {
    super.addGeneratedFile(file, stampOfFile);
    transitivelyAffectedFiles.put(file, stampOfFile);
  }
  
  public void addModuleDependency(Result mod) throws IOException {
    super.addModuleDependency(mod);
    transitivelyAffectedFiles.putAll(mod.getTransitivelyAffectedFileStamps());
  }

  private Map<Path, Integer> getTransitivelyAffectedFileStamps() {
    if (transitivelyAffectedFiles == null) {
      final Map<Path, Integer> deps = new HashMap<>();
      
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
    FileCommands.writeToFile(file, content);
    addGeneratedFile(file);
  }

  public void addEditorService(IStrategoTerm service) {
    editorServices.add(service);
  }
  
  public List<IStrategoTerm> getEditorServices() {
    if (desugaringsFile != null && FileCommands.exists(desugaringsFile))
      return ATermCommands.registerSemanticProvider(editorServices, desugaringsFile);
    return editorServices;
  }
  
  @Override
  protected boolean isConsistentExtend() {
    return desugaringsFile == null || FileCommands.exists(desugaringsFile);
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
    desugaringsFile = jarfile;
  }
  
  public Path getDesugaringsFile() {
    return desugaringsFile;
  }
  
  public Path getPersistentPath() {
    return persistentPath;
  }
  
  public boolean hasFailed() {
    return failed;
  }
  
  public void setFailed(boolean hasFailed) {
    this.failed = hasFailed;
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


  public boolean isParseResult() {
    return parseResultPath != null;
  }
  
  public Path getParseResultPath() {
    return parseResultPath;
  }
  
  @Override
  public void writeEntity(ObjectOutputStream oos) throws IOException {
    super.writeEntity(oos);
    
//    oos.writeObject(parseResultPath);
    
//    oos.writeObject(deferredSourceFiles = Collections.unmodifiableMap(deferredSourceFiles));
  }
  
  @Override
//  @SuppressWarnings("unchecked")
  public void readEntity(ObjectInputStream ois) throws IOException, ClassNotFoundException {
    super.readEntity(ois);
    
//    parseResultPath = (Path) ois.readObject();
//    deferredSourceFiles =   (Map<Set<? extends Path>, Set<? extends Path>>) ois.readObject();
  }
  
  public static Result read(Stamper stamper, Path p) throws ClassNotFoundException, IOException {
    return read(Result.class, stamper, p);
  }
  
  public static Result read(Stamper stamper, Path compileDep, Path editedDep, Map<RelativePath, Integer> editedSourceFiles) throws IOException, ClassNotFoundException {
    return read(Result.class, stamper, compileDep, editedDep, editedSourceFiles);
  }

  public static Result create(Stamper stamper, Path compileDep, Path editedDep, Set<RelativePath> sourceFiles, Map<RelativePath, Integer> editedSourceFiles) throws IOException {
    return create(Result.class, stamper, compileDep, editedDep, sourceFiles, editedSourceFiles);
  }

  
  /**
   * Moves this result and the files generated by this result to the given target directory.
   * @return the moved result.
   */
  public Result moveTo(Path targetDir, boolean isParseResult) throws IOException {
    // FIXME
    return this;
    
//    Result res = new Result(isParseResult ? targetDir : null);
//
//    res.moduleDependencies = new HashMap<Path, Integer>();
//    for (Path dep : moduleDependencies.keySet()) {
//      Result other = readDependencyFile(dep);
//      if (other.isParseResult())
//        other.moveTo(targetDir, isParseResult);
//      res.addModuleDependency(other);
//    }
//    
//    res.circularModuleDependencies = circularModuleDependencies;
//    res.externalFileDependencies = externalFileDependencies;
//    res.collectedErrors = collectedErrors;
//    res.parseErrors = parseErrors;
//    res.sugaredSyntaxTree = sugaredSyntaxTree;
//    res.sourceFile = sourceFile;
//    res.sourceFileHash = sourceFileHash;
//    res.transitivelyAffectedFiles = transitivelyAffectedFiles;
//    res.failed = failed;
//    res.deferredSourceFiles = deferredSourceFiles;
//    res.editorServices = editorServices;
//    
//    res.desugaringsFile = FileCommands.tryCopyFile(parseResultPath, targetDir, desugaringsFile);
//    res.parseTableFile = FileCommands.tryCopyFile(parseResultPath, targetDir, parseTableFile);
//    res.generationLog = FileCommands.tryCopyFile(parseResultPath, targetDir, generationLog);
//
//    res.generatedFiles = new HashMap<Path, Integer>(generatedFiles.size());
//    for (Entry<Path, Integer> e : generatedFiles.entrySet()) {
//      Path p = FileCommands.tryCopyFile(parseResultPath, targetDir, e.getKey());
//      res.registerGeneratedFile(p, FileCommands.fileHash(p));
//    }
//    
//    RelativePath wasDep = FileCommands.getRelativePath(parseResultPath, persistentPath);
//    Path dep = persistentPath;
//    if (wasDep != null)
//      dep = new RelativePath(targetDir, wasDep.getRelativePath());
//    
//    res.writeDependencyFile(dep);
//    
//    return res;
  }

}
