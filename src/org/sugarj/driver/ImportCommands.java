package org.sugarj.driver;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collections;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.shared.BadTokenException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.strategoxt.lang.StrategoException;
import org.sugarj.AbstractBaseProcessor;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.Environment;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;
import org.sugarj.util.Pair;

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
  private RelativePath resolveModule(IStrategoTerm term, IStrategoTerm toplevelDecl, boolean asModel) throws TokenExpectedException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    if (ATermCommands.isApplication(term, "TransApp")) {
      IStrategoTerm model = ATermCommands.getApplicationSubterm(term, "TransApp", 1);
      IStrategoTerm transformation = ATermCommands.getApplicationSubterm(term, "TransApp", 0);
      Pair<String, Boolean> transformedModel = transformModel(model, transformation, toplevelDecl);
      if (transformedModel != null) {
        if (asModel)
          return ModuleSystemCommands.importModel(transformedModel.a, environment, driverResult);
        else
          return ModuleSystemCommands.importStratego(transformedModel.a, environment, driverResult);
      }
      return null;
    }
    
    String path = baseProcessor.getModulePath(term);
    if (path.contains("/")) {
      boolean isCircularImport = driver.prepareImport(toplevelDecl, path);
      if (isCircularImport)
        return null;
      
      if (asModel)
        return ModuleSystemCommands.importModel(path, environment, driverResult);
      else
        return ModuleSystemCommands.importStratego(path, environment, driverResult);
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
   * @param toplevelDecl
   * @param environment
   * @param driver
   * @return a pair consisting of the path to the transformed model and a flag indicating a circular import (if true). 
   */
  public Pair<String, Boolean> transformModel(IStrategoTerm model, IStrategoTerm transformation, IStrategoTerm toplevelDecl) throws TokenExpectedException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    RelativePath modelPath = resolveModule(model, toplevelDecl, true);
    RelativePath transformationPath = resolveModule(transformation, toplevelDecl, false);
    
    if (modelPath == null) {
      // something's wrong
      String name;
      try {
        name = baseProcessor.getModulePath(model);
      } catch (Exception e) {
        name = model.toString();
      }
      driver.setErrorMessage(toplevelDecl, "model not found " + name);
      return null;
    }
    if (transformationPath == null) {
      // something's wrong
      String name;
      try {
        name = baseProcessor.getModulePath(transformation);
      } catch (Exception e) {
        name = transformation.toString();
      }
      driver.setErrorMessage(toplevelDecl, "transformation not found " + name);
      return null;
    }

    Log.log.beginTask("Transform model " + FileCommands.fileName(modelPath) + " with transformation " + FileCommands.fileName(transformationPath), Log.TRANSFORM);
    try {
      RelativePath transformedModelPath = Renaming.getTransformedModelSourceFilePath(modelPath, transformationPath, environment);
//      String transformedModelPath = FileCommands.dropExtension(transformedModelSourceFile.getRelativePath());
      Pair<Result, Boolean> transformedModelResult = ModuleSystemCommands.locateResult(transformedModelPath.getRelativePath(), environment, environment.getMode().getModeForRequiredModules());
  
      if (transformedModelResult.a != null && transformedModelResult.b) {
        // result of transformation is already up-to-date, nothing to do here.
        driverResult.addModuleDependency(transformedModelResult.a);
        return Pair.create(FileCommands.dropExtension(transformedModelPath.getRelativePath()), false);
      }
      else {
        // transform the model, prepare the import of the resulting code.
        IStrategoTerm transformedModel = executeTransformation(modelPath, transformationPath, toplevelDecl);
        String transformedModelText = ATermCommands.atermToString(transformedModel);
        driverResult.generateFile(transformedModelPath, transformedModelText);
        
        boolean isCircularImport = driver.prepareImport(toplevelDecl, transformedModelPath.getRelativePath());
        return Pair.create(FileCommands.dropExtension(transformedModelPath.getRelativePath()), isCircularImport);
      }
    } finally {
      Log.log.endTask();
    }
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
    String strat = "main-" + FileCommands.dropExtension(transformationPath.getRelativePath()).replace('/', '_');
    Pair<Result, Boolean> transformationResult = ModuleSystemCommands.locateResult(FileCommands.dropExtension(transformationPath.getRelativePath()), environment, environment.getMode().getModeForRequiredModules());
    
    if (transformationResult.a == null)
      throw new IllegalStateException("Could not find compiled transformation.");
    
    Path trans = str.compile(transformationPath, strat, transformationResult.a.getTransitivelyAffectedFiles(), baseProcessor.getLanguage().getPluginDirectory());
    
//    IStrategoTerm transformationInput = 
//        ATermCommands.makeTuple(
//            modelTerm, 
//            ATermCommands.makeString(FileCommands.dropExtension(model.getRelativePath()), null),
//            ATermCommands.makeString(FileCommands.dropExtension(transformationPath.getRelativePath()), null));

    try {
      IStrategoTerm transformedTerm = STRCommands.execute(strat, trans, modelTerm, baseProcessor.getInterpreter());

      // local renaming of model name according to transformation
      IStrategoTerm renamedTransformedModel = renameModel(transformedTerm, modelPath, Renaming.getTransformedModelSourceFilePath(modelPath, transformationPath, environment), trans, toplevelDecl);

      return renamedTransformedModel;
    } catch (StrategoException e) {
      String msg = "Failed to apply transformation " + transformationPath.getRelativePath() + " to model " + modelPath.getRelativePath() + ": " + e.getMessage();
      driver.setErrorMessage(toplevelDecl, msg);
      throw new StrategoException(msg);
    }
  }
  
  private IStrategoTerm renameModel(IStrategoTerm transformedModel, RelativePath modelPath, RelativePath transformedModelPath, Path compiledTrans, IStrategoTerm toplevelDecl) {
    FromTo renaming = new FromTo(modelPath, transformedModelPath);
    IStrategoTerm map = Renaming.makeRenamingHashtable(Collections.singletonList(renaming));
    IStrategoTerm[] targs = new IStrategoTerm[] {map};
    try {
      return STRCommands.execute("apply-renamings", targs, compiledTrans, transformedModel, baseProcessor.getInterpreter());
    } catch (IOException e) {
      String msg = "Failed to rename transformedModel " + transformedModelPath + " from " + renaming.from + " to " + renaming.to + ": " + e.getMessage();
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
}
