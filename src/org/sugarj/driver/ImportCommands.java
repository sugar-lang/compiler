package org.sugarj.driver;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.shared.BadTokenException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.strategoxt.lang.StrategoException;
import org.sugarj.AbstractBaseProcessor;
import org.sugarj.cleardep.CompilationUnit;
import org.sugarj.cleardep.Synthesizer;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.common.util.Pair;
import org.sugarj.driver.Renaming.FromTo;

/**
 * @author seba
 */
public class ImportCommands {
  
  private AbstractBaseProcessor baseProcessor;
  private Environment environment;
  private Driver driver;
  private DriverParameters params;
  private Result driverResult;
  private STRCommands str;
  
  private String currentTransName;
  private String currentModelName;
  
  public ImportCommands(AbstractBaseProcessor baseProcessor, Environment environment, Driver driver, DriverParameters params, Result driverResult, STRCommands str) {
    this.baseProcessor = baseProcessor;
    this.environment = environment;
    this.driver = driver;
    this.params = params;
    this.driverResult = driverResult;
    this.str = str;
  }

  /**
   * Resolve module
   * 
   * @param term 
   * @param toplevelDecl
   * @param asModel If true, looks for models. If false, looks for transformations.
   * @return
   */
  public Pair<RelativePath, Boolean> resolveModule(IStrategoTerm term, boolean asModel) throws TokenExpectedException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    if (ATermCommands.isApplication(term, "TransApp")) {
      IStrategoTerm model = ATermCommands.getApplicationSubterm(term, "TransApp", 1);
      IStrategoTerm transformation = ATermCommands.getApplicationSubterm(term, "TransApp", 0);
      
      Pair<RelativePath, Boolean> resolvedModel = resolveModule(model, true);
      if (resolvedModel == null) {
        driver.setErrorMessage(model, "model not found: " + model.toString());
        return null;
      }
      
      Pair<RelativePath, Boolean> resolvedTransformation = resolveModule(transformation, false);
      if (resolvedTransformation == null) {
        driver.setErrorMessage(transformation, "transformation not found: " + transformation.toString());
        return null;
      }
      
      Pair<String, Boolean> transformedModel = transformModel(resolvedModel.a, resolvedTransformation.a, term);
      
      if (transformedModel == null)
        return null;
      
      RelativePath p;
      if (asModel)
        p = ModuleSystemCommands.importModel(transformedModel.a, environment, driverResult);
      else
        p = ModuleSystemCommands.importStratego(transformedModel.a, environment, driverResult);
      
      if (p == null)
        return null;
      return Pair.create(p, resolvedModel.b || resolvedTransformation.b || transformedModel.b);
    }
    
    String path = baseProcessor.getModulePath(term);
    if (path.contains("/")) {
      boolean isCircularImport = driver.prepareImport(term, path, null);
      if (isCircularImport)
        throw new RuntimeException("Circular import in transformation application");
      
      RelativePath p;
      if (asModel)
        p = ModuleSystemCommands.importModel(path, environment, driverResult);
      else
        p = ModuleSystemCommands.importStratego(path, environment, driverResult);
      
      if (p == null)
        return null;
      return Pair.create(p, isCircularImport);
    }
    
    throw new RuntimeException("TODO support non-qualifed transformations and model paths");
    // TODO support non-qualifed transformations and model paths
    
//    return null;
  }

  /**
   * Transforms the given model with the given transformation.
   * 
   * @param model AST part of the import that denotes the model.
   * @param transformation AST part of the import that denotes the transformation.
   * @param term
   * @param environment
   * @param driver
   * @return a pair consisting of the path to the transformed model and a flag indicating a circular import (if true). 
   */
  public Pair<String, Boolean> transformModel(RelativePath modelPath, RelativePath transformationPath, IStrategoTerm term) throws TokenExpectedException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    Log.log.beginTask("Transform model " + FileCommands.fileName(modelPath) + " with transformation " + FileCommands.fileName(transformationPath), Log.TRANSFORM);
    try {
      RelativePath transformedModelPath = Renaming.getTransformedModelSourceFilePath(modelPath, transformationPath, environment);
      Result transformedModelResult = ModuleSystemCommands.locateResult(transformedModelPath.getRelativePath(), environment, environment.<Result>getMode().getModeForRequiredModules());
      
      Result modelResult = ModuleSystemCommands.locateResult(modelPath.getRelativePath(), environment, environment.<Result>getMode().getModeForRequiredModules());
      Result transformationResult = ModuleSystemCommands.locateResult(transformationPath.getRelativePath(), environment, environment.<Result>getMode().getModeForRequiredModules());
      if (transformationResult == null || modelResult == null | transformationResult == null)
        throw new IllegalStateException("Could not locate all required compilation results: " + transformationResult + ", " + modelResult + ", " + transformationResult);
      Set<CompilationUnit> synModules = new HashSet<>();
      synModules.add(modelResult);
      synModules.add(transformationResult);
      Set<Path> synFiles = new HashSet<>();
      synFiles.add(modelPath);
      synFiles.add(transformationPath);
      Synthesizer syn = new Synthesizer(environment.getStamper(), synModules, synFiles);

      String modulePath = FileCommands.dropExtension(transformedModelPath.getRelativePath());
      IStrategoTerm importTerm = baseProcessor.reconstructImport(modulePath);
      
      if (transformedModelResult != null && transformedModelResult.isConsistent(null, environment.getMode().getModeForRequiredModules())) {
        // result of transformation is already up-to-date, nothing to do here.
        boolean isCircularImport = driver.prepareImport(importTerm, modulePath, syn);
        return Pair.create(FileCommands.dropExtension(transformedModelPath.getRelativePath()), isCircularImport);
      }
      else {
        // transform the model, prepare the import of the resulting code.
        IStrategoTerm transformedModel = executeTransformation(modelPath, transformationPath, term);
        String transformedModelText = ATermCommands.atermToString(transformedModel);
        driverResult.generateFile(transformedModelPath, transformedModelText);
        
        boolean isCircularImport = driver.prepareImport(importTerm, modulePath, syn);

        transformedModelResult = ModuleSystemCommands.locateResult(transformedModelPath.getRelativePath(), environment, environment.<Result>getMode().getModeForRequiredModules());
        checkCommunicationIntegrity(transformedModelResult, term);
        
        return Pair.create(FileCommands.dropExtension(transformedModelPath.getRelativePath()), isCircularImport);
      }
    } finally {
      Log.log.endTask();
    }
  }
  
  private void checkCommunicationIntegrity(Result transformedModelResult, IStrategoTerm toplevelDecl) {
    Set<CompilationUnit> usedModules = new HashSet<>();
    usedModules.addAll(transformedModelResult.getModuleDependencies());
    usedModules.addAll(transformedModelResult.getCircularModuleDependencies());

    if (transformedModelResult.getSynthesizer() == null)
      throw new IllegalArgumentException("Argument " + transformedModelResult + " must be a generated module with getSynthesizer() != null");
    
    Set<CompilationUnit> allowedModules = new HashSet<>(transformedModelResult.getSynthesizer().generatorModules);
    for (CompilationUnit mod : transformedModelResult.getSynthesizer().generatorModules) {
      allowedModules.addAll(mod.getModuleDependencies());
      allowedModules.addAll(mod.getCircularModuleDependencies());
    }
    
    Set<CompilationUnit> hiddenDependencies = new HashSet<>();
    for (CompilationUnit m : usedModules)
      if (!checkCommunicationIntegrityDepOK(m, allowedModules))
        hiddenDependencies.add(m);
    
    if (!hiddenDependencies.isEmpty()) {
      StringBuilder builder = new StringBuilder();
      for (Iterator<CompilationUnit> it = hiddenDependencies.iterator(); it.hasNext(); ) {
        builder.append(it.next().getName());
        if (it.hasNext())
          builder.append(", ");
      }
      
      driver.setErrorMessage(toplevelDecl, "Generated model contains hidden dependencies " + builder.toString() + ".");
    }
  }
  
  private boolean checkCommunicationIntegrityDepOK(CompilationUnit m, Set<CompilationUnit> allowed) {
    if (allowed.contains(m))
      return true;
    if (m.getSynthesizer() == null)
      return false;
    for (CompilationUnit m2 : m.getSynthesizer().generatorModules)
      if (!checkCommunicationIntegrityDepOK(m2, allowed))
        return false;
    return true;
  }

  /**
   * Apply the transformation to the model and return the result.
   * 
   * Assumes that the model and transformation are already registered as dependencies with the current driver result.
   * 
   * @param modelPath Path to the *.model file that contains the Aterm model.
   * @param transformationPath Path to the *.str transformation.
   */
  private IStrategoTerm executeTransformation(RelativePath modelPath, RelativePath transformationPath, IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, BadTokenException, InvalidParseTableException, SGLRException {
    IStrategoTerm modelTerm = ATermCommands.atermFromFile(modelPath.getAbsolutePath());
    String modelName = FileCommands.dropExtension(modelPath.getRelativePath());
    String transName = FileCommands.dropExtension(transformationPath.getRelativePath());
    String strat = "main-" + transName.replace('/', '_');
    Result transformationResult = ModuleSystemCommands.locateResult(FileCommands.dropExtension(transformationPath.getRelativePath()), environment, environment.<Result>getMode().getModeForRequiredModules());
    
    if (transformationResult == null)
      throw new IllegalStateException("Could not find compiled transformation.");
    
    Path trans = str.compile(transformationPath, transformationResult.getTransitivelyAffectedFiles(), baseProcessor.getLanguage().getPluginDirectory());
    
//    IStrategoTerm transformationInput = 
//        ATermCommands.makeTuple(
//            modelTerm, 
//            ATermCommands.makeString(FileCommands.dropExtension(model.getRelativePath()), null),
//            ATermCommands.makeString(FileCommands.dropExtension(transformationPath.getRelativePath()), null));

    IStrategoTerm transformedTerm;
    try {
      currentModelName = modelName;
      currentTransName = transName;
      transformedTerm = STRCommands.execute(strat, trans, modelTerm, baseProcessor.getInterpreter());
    } catch (StrategoException e) {
      String msg = "Failed to apply transformation " + transformationPath.getRelativePath() + " to model " + modelPath.getRelativePath() + ": " + e.getMessage();
//      driver.setErrorMessage(toplevelDecl, msg);
      throw new StrategoException(msg, e);
    } finally {
      modelName = null;
      currentTransName = null;
    }
    
    // local renaming of model name according to transformation
    IStrategoTerm renamedTransformedModel = renameModel(transformedTerm, modelPath, Renaming.getTransformedModelSourceFilePath(modelPath, transformationPath, environment), trans, toplevelDecl);

    return renamedTransformedModel;
  }
  
  public void setCurrentTransName(String currentTransName) {
    this.currentTransName = currentTransName;
  }
  public String getCurrentTransName() {
    return currentTransName;
  }
  public void setCurrentModelName(String currentModelName) {
    this.currentModelName = currentModelName;
  }
  public String getCurrentModelName() {
    return currentModelName;
  }

  
  private IStrategoTerm renameModel(IStrategoTerm transformedModel, RelativePath modelPath, RelativePath transformedModelPath, Path compiledTrans, IStrategoTerm toplevelDecl) {
    FromTo renaming = new FromTo(modelPath, transformedModelPath);
    return renameModel(transformedModel, renaming, compiledTrans, toplevelDecl, transformedModelPath.toString());
  }

  public IStrategoTerm renameModel(IStrategoTerm model, FromTo renaming, Path compiledTrans, IStrategoTerm toplevelDecl, String modelDesc) {
    IStrategoTerm map = Renaming.makeRenamingHashtable(Collections.singletonList(renaming));
    IStrategoTerm[] targs = new IStrategoTerm[] {map};
    try {
      return STRCommands.execute("apply-renamings", targs, compiledTrans, model, baseProcessor.getInterpreter());
    } catch (StrategoException | IOException e) {
      String msg = "Failed to rename transformedModel " + modelDesc + " from " + renaming.from + " to " + renaming.to + ": " + e.getMessage();
      driver.setErrorMessage(toplevelDecl, msg);
      throw new StrategoException(msg);
    }
  }

  
  /**
   * Retrieves the right-most model in the given transformation application and returns the model's name.
   * 
   * @param appl
   * @param base processor
   * @return
   */
  public static String getTransformationApplicationModelPath(IStrategoTerm appl, AbstractBaseProcessor baseProcessor) {
    if (ATermCommands.isApplication(appl, "TransApp"))
      return getTransformationApplicationModelPath(appl.getSubterm(1), baseProcessor);
    return baseProcessor.getModulePath(appl);
  }

  public String computeModulePathOfImport(IStrategoTerm importTerm) {
    if (!baseProcessor.getLanguage().isTransformationImport(importTerm))
      return baseProcessor.getModulePathOfImport(importTerm);
    
    IStrategoTerm appl = baseProcessor.getLanguage().getTransformationApplication(importTerm);
    return computeModulePathOfAppl(appl);
  }

  private String computeModulePathOfAppl(IStrategoTerm term) {
    if (!ATermCommands.isApplication(term, "TransApp"))
      return baseProcessor.getModulePath(term);
    
    IStrategoTerm model = ATermCommands.getApplicationSubterm(term, "TransApp", 1);
    IStrategoTerm transformation = ATermCommands.getApplicationSubterm(term, "TransApp", 0);
    
    String modelPath = computeModulePathOfAppl(model);
    String transformationPath = computeModulePathOfAppl(transformation);
    
    return modelPath + "__" + transformationPath.replace('/', '_');
  }
}
