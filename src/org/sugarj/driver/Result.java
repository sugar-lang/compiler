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
import org.sugarj.common.deps.Module;
import org.sugarj.common.deps.Stamper;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Result extends Module {

  /**
   *  This is a parse result if it was produced during parsing.
   */
  private Path parseResultPath;

  private List<IStrategoTerm> editorServices = new LinkedList<IStrategoTerm>();
  private List<String> collectedErrors = new LinkedList<String>();
  private Set<BadTokenException> parseErrors = new HashSet<BadTokenException>();
  private IStrategoTerm sugaredSyntaxTree = null;
  private IStrategoTerm desugaredSyntaxTree;
  private Path parseTableFile;
  private Path desugaringsFile;
  private boolean failed = false;
  
  /**
   * deferred source files (*.sugj) -> 
   * to-be-compiled source files (e.g., *.java + generated SourceFileContent) 
   */
  private Map<Path, String> deferredSourceFiles = new HashMap<>();
  
  public Result() { /* for serialization only */ }
  public Result(Stamper stamper, Path parseResultPath) {
    super(stamper);
    this.parseResultPath = parseResultPath;
  }
  
  public void generateFile(RelativePath file, String content) throws IOException {
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
  
  void delegateCompilation(Result delegate, Path compileFile, String source, boolean hasNonBaseDec) {
    delegate.deferredSourceFiles.putAll(deferredSourceFiles);
    if (!source.isEmpty() || hasNonBaseDec)
      delegate.deferredSourceFiles.put(compileFile, source);
  }
  
  boolean isDelegateOf(Set<? extends Path> sourceFiles) {
    return !Collections.disjoint(deferredSourceFiles.keySet(), sourceFiles);
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

  public Map<Path, String> getDeferredSourceFiles() {
    return deferredSourceFiles;
  }


  public boolean isParseResult() {
    return parseResultPath != null;
  }
  
  public Path getParseResultPath() {
    return parseResultPath;
  }
  
  @Override
  public void writeEntity(ObjectOutputStream oos) throws IOException {
//    oos.writeObject(parseResultPath);
    
    oos.writeObject(deferredSourceFiles = Collections.unmodifiableMap(deferredSourceFiles));
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public void readEntity(ObjectInputStream ois) throws IOException, ClassNotFoundException {
//    parseResultPath = (Path) ois.readObject();
    
    deferredSourceFiles =  (Map<Path, String>) ois.readObject();
  }
  
  public static Result read(Stamper stamper, Path p) throws ClassNotFoundException, IOException {
    return read(Result.class, stamper, p);
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
  
  public static void cacheInMemory(Path dep, Result result) {
    // FIXME
//    synchronized (results) {
//      results.put(dep, result);
//      results.put(dep, new SoftReference<Result>(result));
//    }
  }
 
//  public static Result getCachedResult(Path dep) throws IOException {
//    Result result = null;
//    synchronized (results) {
//      result = results.get(dep);
////      SoftReference<Result> ref = results.get(dep);
////      if (ref != null)
////        result = ref.get();
//    }
//    if (result != null && !result.hasPersistentVersionChanged())
//      return result;
//    return null;
//  }

}
