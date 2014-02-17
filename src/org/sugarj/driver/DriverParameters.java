package org.sugarj.driver;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.common.Environment;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.declprovider.SourceToplevelDeclarationProvider;
import org.sugarj.driver.declprovider.TermToplevelDeclarationProvider;
import org.sugarj.driver.declprovider.ToplevelDeclarationProvider;

/**
 * @author Sebastian Erdweg
 */
public class DriverParameters {
  /**
   * Processing environment.
   */
  public final Environment env;
  
  /**
   * Base language that the driver processes files for.
   */
  public final AbstractBaseLanguage baseLang;
  
  /**
   * Files to process.
   */
  public final Set<RelativePath> sourceFiles;
  
  /**
   * Source files that have been edited and differ
   * from the stored version of the file.
   */
  public final Map<RelativePath, String> editedSources;
  
  /**
   * Provides toplevel declarations for all source files.
   */
  public final ToplevelDeclarationProvider declProvider;
  
  /**
   * Currently running driver chain, where each one called `subcompile` on the next one.
   */
  public final List<Driver> currentlyProcessing;
  
  /**
   * Eclipse progress monitor.
   */
  public final IProgressMonitor monitor;
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, Map<RelativePath, String> editedSources, IProgressMonitor monitor) throws IOException {
    return create(env, baseLang, sourceFile, editedSources, new LinkedList<Driver>(), monitor);
  }
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, Map<RelativePath, String> editedSources, List<Driver> currentlyProcessing, IProgressMonitor monitor) throws IOException {
    String source = editedSources.get(sourceFile);
    if (source == null)
      source = FileCommands.readFileAsString(sourceFile);
    return new DriverParameters(
        env,
        baseLang,
        Collections.singleton(sourceFile),
        editedSources,
        new SourceToplevelDeclarationProvider(source, sourceFile),
        currentlyProcessing,
        monitor);
  }
  
  public static DriverParameters create(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IStrategoTerm termSource, Map<RelativePath, String> editedSources, List<Driver> currentlyProcessing, IProgressMonitor monitor) {
    return new DriverParameters(
        env,
        baseLang,
        Collections.singleton(sourceFile),
        editedSources,
        new TermToplevelDeclarationProvider(termSource, sourceFile, env),
        currentlyProcessing,
        monitor);
  }
  
  private DriverParameters(
      Environment env,
      AbstractBaseLanguage baseLang,
      Set<RelativePath> sourceFiles, 
      Map<RelativePath, String> editedSource, 
      ToplevelDeclarationProvider declProvider, 
      List<Driver> currentlyProcessing, 
      IProgressMonitor monitor) {
    this.env = env;
    this.baseLang = baseLang;
    this.sourceFiles = sourceFiles;
    this.editedSources = editedSource;
    this.declProvider = declProvider;
    this.currentlyProcessing = currentlyProcessing;
    this.monitor = monitor;
  }
  
}
