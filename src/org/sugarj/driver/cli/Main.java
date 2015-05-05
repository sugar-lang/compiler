package org.sugarj.driver.cli;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.BaseLanguageRegistry;
import org.sugarj.cleardep.build.BuildManager;
import org.sugarj.cleardep.stamp.FileHashStamper;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.DriverBuildRequest;
import org.sugarj.driver.DriverInput;
import org.sugarj.driver.Environment;
import org.sugarj.driver.ModuleSystemCommands;
import org.sugarj.driver.Result;
import org.sugarj.stdlib.StdLib;

/**
 * @author seba
 */
public class Main {
  
  private static void exit(int i, Environment e) {
    if (e.isTerminateJVMAfterProcessing()) {
      System.exit(i);
    }
  }

  public static void main(String[] args) throws Throwable {

    Environment environment = getConsoleEnvironment();
    
    Set<RelativePath> allInputFiles = new HashSet<RelativePath>();
    
    try {
      String[] sources = DriverCLI.handleOptions(args, environment);
      
      for (String source : sources) {
        RelativePath sourceLocation = ModuleSystemCommands.locateSourceFile(source, environment.getSourcePath());
        
        if (sourceLocation == null) {
          Log.log.logErr("Could not locate source file \"" + source +"\".", Log.ALWAYS);
          continue;
        }

        allInputFiles.add(sourceLocation);
      }
      
      IProgressMonitor monitor = new NullProgressMonitor();
      
      for (final RelativePath sourceFile : allInputFiles) {
        AbstractBaseLanguage lang = BaseLanguageRegistry.getInstance().getBaseLanguage(FileCommands.getExtension(sourceFile));
        if (null == lang)
          throw new RuntimeException("Unknown file extension \"" + FileCommands.getExtension(sourceFile) + "\".");
        
        DriverInput input = new DriverInput(environment, lang, sourceFile, monitor);
        Result res = BuildManager.build(new DriverBuildRequest(input));
    
        DriverCLI.CLI_ExitValue returnValue = DriverCLI.processResultCLI(res, sourceFile, new File(".").getAbsolutePath());
        switch (returnValue) {
        case SUCCESS:
          exit (0, environment);
        case COMPILATION_ERROR:
          exit (1, environment);
        case DSL_ANALYSIS_ERROR:
          exit (2, environment);
        case DSL_ANALYSIS_WARNING:
          exit (3, environment);
        case DSL_ANALYSIS_NOTE:
          exit (4, environment);
        case FAILURE: 
        default:
          exit (100, environment);
        }
      }
      
    } catch (Exception e) {
      e.printStackTrace();
      exit(100, environment);
    } catch (CLIError e) {
      Log.log.log(e.getMessage(), Log.ALWAYS);
      Log.log.log("", Log.ALWAYS);
      e.showUsage();
      exit(1, environment);
    }
    exit(0, environment);
  }
  
  // without running eclipse platform,
  // set up a default environment reasonable for command-line execution.
  private static Environment getConsoleEnvironment() {
    Environment environment = new Environment(StdLib.stdLibDir, FileHashStamper.instance);
    environment.setCacheDir(new RelativePath(new AbsolutePath(FileCommands.TMP_DIR), ".sugarjcache"));
    environment.addToSourcePath(new AbsolutePath("."));
    environment.setAtomicImportParsing(false);
    environment.setNoChecking(true);
    environment.setBin(new AbsolutePath("."));
    
    for (String cp : System.getProperty("java.class.path").split(System.getProperty("path.separator"))) {
      if (cp.length() > 0)
        environment.addToIncludePath(new AbsolutePath(cp));
    }
    return environment;
  }

}
