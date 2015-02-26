package org.sugarj.driver;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.cleardep.build.BuildRequirement;
import org.sugarj.cleardep.stamp.Stamp;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;

/**
 * @author Sebastian Erdweg
 */
public class DriverInput implements Serializable {
  private static final long serialVersionUID = -8640182333713236865L;

//  public final transient boolean forEditor;
  
  /**
   * Processing environment.
   */
  private final Environment env;
  
  /**
   * Base language that the driver processes files for.
   */
  public final AbstractBaseLanguage baseLang;
  
  /**
   * Files to process.
   */
  public final RelativePath sourceFilePath;
  
  /**
   * Edited content of `sourceFilePath`, or null if unchanged.
   */
  public final String editedSource;
  
  /**
   * Edited stamp of `sourceFilePath`, or null if unchanged..
   */
  public final Stamp editedSourceStamp;
  
  /**
   * Currently active renamings;
   */
  public final List<FromTo> renamings;
  
  /**
   * Eclipse progress monitor.
   */
  public final transient IProgressMonitor monitor;
  
  /**
   * Build requirements injected into this compiler call. Needed to allow compilation of generated files.
   */
  public final BuildRequirement<?, ?, ?, ?>[] injectedRequirements;
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    this(env, baseLang, sourceFile, null, null, new LinkedList<FromTo>(), monitor, injectedRequirements);
  }
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, String editedSource, Stamp editedSourceStamp, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    this(env, baseLang, sourceFile, editedSource, editedSourceStamp, new LinkedList<FromTo>(), monitor, injectedRequirements);
  }
  
  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, IStrategoTerm termSource, String editedSource, Stamp editedSourceStamp, List<FromTo> renamings, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) throws IOException {
    this(
        env,
        baseLang,
        sourceFile,
        editedSource,
        editedSourceStamp,
        renamings,
        monitor,
        injectedRequirements);
  }

  public DriverInput(Environment env, AbstractBaseLanguage baseLang, RelativePath sourceFile, String editedSource, Stamp editedSourceStamp, List<FromTo> renamings, IProgressMonitor monitor, BuildRequirement<?, ?, ?, ?>... injectedRequirements) {
    this.env = env;
    this.baseLang = baseLang;
    this.sourceFilePath = sourceFile;
    this.editedSource = editedSource;
    this.editedSourceStamp = editedSourceStamp;
    this.renamings = renamings;
    this.monitor = monitor;
    this.injectedRequirements = injectedRequirements;
  }
  
  public Environment getOriginalEnvironment() {
    return env;
  }
}
