package org.sugarj.driver;

import static org.sugarj.common.Log.log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sugarj.AbstractBaseProcessor;
import org.sugarj.cleardep.BuildUnit;
import org.sugarj.cleardep.BuildUnit.ModuleVisitor;
import org.sugarj.cleardep.build.BuildRequest;
import org.sugarj.cleardep.dependency.FileRequirement;
import org.sugarj.cleardep.dependency.Requirement;
import org.sugarj.cleardep.stamp.Stamp;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class ModuleSystemCommands {
  
  
    /**
     * 
     * @param modulePath
     * @param importTerm
     * @param javaOutFile
     * @param interp
     * @param driver
     * @return true iff a class file existed.
     * @throws IOException
     */
    public static RelativePath importBinFile(String modulePath, Environment environment, AbstractBaseProcessor baseProcessor, Driver driver) throws IOException {
      String ext = baseProcessor.getLanguage().getBinaryFileExtension();
      if (ext == null)
        // language is interpreted
        ext = baseProcessor.getLanguage().getBaseFileExtension();
      RelativePath clazz = searchFile(modulePath, ext, environment, driver);
      if (clazz == null)
        return null;
      
      log.log("Found language-specific declaration for " + modulePath, Log.IMPORT);
      return clazz;
    }
    
  /**
   * 
   * @param modulePath
   * @param currentGrammarModule
   * @param availableSDFImports
   * @param driver
   * @return path to new grammar or null if no sdf file existed.
   * @throws IOException 
   */
  public static RelativePath importSdf(String modulePath, Environment environment, Driver driver) {
    RelativePath sdf = searchFile(modulePath, "sdf", environment, driver);
    
    if (sdf == null)
      return null;
    
    log.log("Found syntax definition for " + modulePath, Log.IMPORT);
    return sdf;
  }
  
  /**
   * 
   * @param modulePath
   * @param currentTransModule
   * @param availableSTRImports
   * @param driver
   * @return path to new Stratego module or null of no str file existed
   * @throws IOException 
   */
  public static RelativePath importStratego(String modulePath, Environment environment, Driver driver) {
    RelativePath str = searchFile(modulePath, "str", environment, driver);
    
    if (str == null)
      return null;

    log.log("Found desugaring for " + modulePath, Log.IMPORT);
    return str;
  }
  
  /**
   * 
   * @param modulePath
   * @param driver
   * @return true iff a serv file existed.
   * @throws IOException
   */
  public static boolean importEditorServices(String modulePath, Environment environment, Driver driver) throws IOException {
    RelativePath serv = searchFile(modulePath, "serv", environment, driver);
    
    if (serv == null)
      return false;
    
    BufferedReader reader = null;
    
    log.beginTask("Incorporation", "Found editor services for " + modulePath, Log.IMPORT);
    try {
      reader = new BufferedReader(new FileReader(serv.getFile()));
      String line;
      
      while ((line = reader.readLine()) != null)
        driver.getCurrentResult().addEditorService(ATermCommands.atermFromString(line));
      
      return true;
    } finally {
      if (reader != null)
        reader.close();
      
      log.endTask();
    }
  }
  
  public static RelativePath importModel(String modulePath, Environment environment, Driver driver) {
    RelativePath model = searchFile(modulePath, "model", environment, driver);
    
    if (model == null)
      return null;

    log.log("Found model for " + modulePath, Log.IMPORT);
    return model;
  }
  
  public static RelativePath locateSourceFile(String path, List<Path> sourcePath) {
    return locateSourceFile (FileCommands.dropExtension(path), FileCommands.getExtension(path), sourcePath);
  }

  public static RelativePath locateSourceFileOrModel(String modulePath, List<Path> sourcePath, AbstractBaseProcessor baseProcessor, Environment environment) {
    RelativePath result = locateSourceFile(modulePath, baseProcessor.getLanguage().getSugarFileExtension(), sourcePath);
    if (result == null)
      result = searchFile(environment.createOutPath(modulePath + ".model"), null);
    if (result == null && baseProcessor.getLanguage().getBaseFileExtension() != null)
      result = locateSourceFile(modulePath, baseProcessor.getLanguage().getBaseFileExtension(), sourcePath);
    return result;
  }

  public static RelativePath locateSourceFile(String modulePath, String extension, List<Path> sourcePath) {
    if (modulePath.startsWith("org/sugarj"))
      return null;
    
    return searchFileInSourceLocationPath(modulePath, extension, sourcePath, null);
  }
  
  /**
   * Registers searched files in the driverResult. Existing and non-existent files are registers,
   * so that the emergence of a file triggers recompilation.
   * 
   * @param relativePath without extension
   * @param fileExtension without leading "."
   * @return RelativePath or null.
   * @throws IOException 
   */
  public static RelativePath searchFile(String relativePath, String fileExtension, Environment environment, Driver driver) {
    RelativePath p = searchFile(environment.createOutPath(relativePath + "." + fileExtension), driver);
    if (p == null) {
      p = searchFileInSearchPath(relativePath, fileExtension, environment.getIncludePath(), driver);
    }
    return p;
  }

  private static RelativePath searchFile(RelativePath file, Driver driver) {
    if (driver != null)
      driver.requires(file);
    if (file.getFile().exists())
      return file;
    
    return null;
  }
	  
  private static RelativePath searchFileInSearchPath(String relativePath, String extension, List<Path> searchPath, Driver driver) {
    for (Path base : searchPath) {
      RelativePath p = searchFile(base, relativePath, extension, driver);
      if (p != null)
        return p;
    }
    
    return null;
  }

  private static RelativePath searchFileInSourceLocationPath(String relativePath, String extension, List<Path> searchPath, Driver driver) {
    for (Path loc : searchPath) {
      RelativePath p = searchFile(loc, relativePath, extension, driver);
      if (p != null)
        return p;
    }
    
    return null;
  }

  private static RelativePath searchFile(Path base, String relativePath, String extension, Driver driver) {
    if (relativePath.startsWith(base.getAbsolutePath())) {
      int sepOffset = relativePath.endsWith(File.separator) ? 0 : 1;
      relativePath = relativePath.substring(base.getAbsolutePath().length() + sepOffset);
    }
    
    if (base.getFile().isDirectory()) {
      RelativePath p = new RelativePath(base, relativePath + "." + extension);
      if (searchFile(p, driver) != null)
        return p;
    }
    
    URLClassLoader cl = null;
    try {
      cl = new URLClassLoader(new URL[] {base.getFile().toURI().toURL()}, null);
      if (cl.getResource(relativePath + "." + extension) != null)
        return new RelativePath(base, relativePath + "." + extension);
    } catch (MalformedURLException e) {
      e.printStackTrace();
    } finally {
      if (cl != null)
        try {
          cl.close();
        } catch (IOException e) {
        }
    }
    
    return null;
  }
  
  public static BuildUnit<Result> locateResult(String modulePath, Environment env, BuildRequest<?, Result, ?, ?> req) throws IOException {
    RelativePath dep = new RelativePath(env.getBin(), FileCommands.dropExtension(modulePath) + ".dep");
    
    BuildUnit<Result> result = BuildUnit.read(dep, req);
    if (result != null)
      return result;
    
    for (Path base : env.getIncludePath()) {
      dep = new RelativePath(base, FileCommands.dropExtension(modulePath) + ".dep");
      
      result = BuildUnit.read(dep, req);
      if (result != null)
        return result;
    }
    
    return null;
  }
  
  public static BuildUnit<Result> locateConsistentResult(String modulePath, Environment env, Map<RelativePath, Stamp> sourceFiles, BuildRequest<?, Result, ?, ?> req) throws IOException {
    RelativePath dep = new RelativePath(env.getBin(), FileCommands.dropExtension(modulePath) + ".dep");
    
    BuildUnit<Result> result = BuildUnit.readConsistent(dep, req, sourceFiles);
    if (result != null)
      return result;
    
    for (Path base : env.getIncludePath()) {
      dep = new RelativePath(base, FileCommands.dropExtension(modulePath) + ".dep");
      
      result = BuildUnit.readConsistent(dep, req, sourceFiles);
      if (result != null)
        return result;
    }
    
    return null;
  }
  
  public static boolean isGenerated(BuildUnit<Result> unit) {
    @SuppressWarnings("unchecked")
    BuildRequest<DriverInput, Result, ?, ?> genBy = (BuildRequest<DriverInput, Result, ?, ?>) unit.getGeneratedBy();
    BuildRequest<?, ?, ?, ?>[] injectedRequirements = genBy.input.injectedRequirements;
    for (int i = 0; i < injectedRequirements.length; i++)
      if (injectedRequirements[i].input instanceof TransformModelBuilder.Input)
        return true;
    return false;
  }
  
  
  public static Map<Path, Stamp> getTransitivelyAffectedFileStamps(BuildUnit<?> unit) {
    final Map<Path, Stamp> deps = new HashMap<>();

    ModuleVisitor<Void> collectAffectedFileStampsVisitor = new ModuleVisitor<Void>() {
      @Override
      public Void visit(BuildUnit<?> mod) {
        for (FileRequirement freq : mod.getGeneratedFileRequirements())
          deps.put(freq.path, freq.stamp);
        for (Requirement req : mod.getRequirements())
          if (req instanceof FileRequirement) {
            FileRequirement freq = (FileRequirement) req;
            deps.put(freq.path, freq.stamp);
          }
        return null;
      }

      @Override
      public Void combine(Void v1, Void v2) {
        return null;
      }

      @Override
      public Void init() {
        return null;
      }

      @Override
      public boolean cancel(Void t) {
        return false;
      }
    };

    unit.visit(collectAffectedFileStampsVisitor);
    return deps;
  }

}
