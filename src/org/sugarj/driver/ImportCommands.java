package org.sugarj.driver;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collections;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.strategoxt.lang.StrategoException;
import org.sugarj.AbstractBaseProcessor;
import org.sugarj.cleardep.build.BuildRequirement;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
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
  private DriverInput input;
  private STRCommands str;
  
  private String currentTransName;
  private String currentModelName;
  
  public ImportCommands(AbstractBaseProcessor baseProcessor, Environment environment, Driver driver, DriverInput input, STRCommands str) {
    this.baseProcessor = baseProcessor;
    this.environment = environment;
    this.driver = driver;
    this.input = input;
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
  public Pair<RelativePath, DriverBuildRequirement> resolveModule(IStrategoTerm term, boolean asModel) throws TokenExpectedException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    if (ATermCommands.isApplication(term, "TransApp")) {
      IStrategoTerm model = ATermCommands.getApplicationSubterm(term, "TransApp", 1);
      IStrategoTerm transformation = ATermCommands.getApplicationSubterm(term, "TransApp", 0);
      
      Pair<RelativePath, DriverBuildRequirement> resolvedModel = resolveModule(model, true);
      Pair<RelativePath, DriverBuildRequirement> resolvedTransformation = resolveModule(transformation, false);
      
      Pair<RelativePath, ? extends BuildRequirement<?,?,?,?>> transformedModel = transformModel(resolvedModel.a, resolvedModel.b, resolvedTransformation.a, resolvedTransformation.b, term);
      RelativePath sourceFile = transformedModel.a;
      DriverBuildRequirement req = driver.subcompile(sourceFile, transformedModel.b);
      return Pair.create(sourceFile, req);
    }
    else {
      String path = baseProcessor.getModulePath(term);
      if (!path.contains("/"))
        // TODO support non-qualifed transformations and model paths
        throw new RuntimeException("TODO support non-qualifed transformations and model paths");

      RelativePath importSourceFile = ModuleSystemCommands.locateSourceFileOrModel(path, input.env.getSourcePath(), baseProcessor, input.env);
      if (importSourceFile == null)
        return null;
      
      DriverBuildRequirement req = driver.subcompile(importSourceFile);
      return Pair.create(importSourceFile, req);
    }
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
  public Pair<RelativePath, ? extends BuildRequirement<?,?,?,?>> transformModel(RelativePath modelPath, DriverBuildRequirement modelReq, RelativePath transformationPath, DriverBuildRequirement transformationReq, IStrategoTerm term) throws TokenExpectedException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    RelativePath transformedModelPath = Renaming.getTransformedModelSourceFilePath(modelPath, transformationPath, environment);
    RelativePath importSourceFile = ModuleSystemCommands.locateSourceFileOrModel(FileCommands.dropExtension(transformedModelPath.getRelativePath()), input.env.getSourcePath(), baseProcessor, input.env);
    
    TransformModelBuilder.Input input = new TransformModelBuilder.Input(
        modelPath,
        modelReq,
        transformationPath,
        transformationReq,
        term, 
        importSourceFile, 
        str, 
        baseProcessor.getLanguage().getPluginDirectory(), 
        this, 
        baseProcessor.getInterpreter());
    BuildRequirement<?, ?, ?, ?> req = new BuildRequirement<>(TransformModelBuilder.factory, input);
    return Pair.create(importSourceFile, req);
  }
  
  
  
//  private void checkCommunicationIntegrity(Result transformedModelResult, IStrategoTerm toplevelDecl) {
//    Set<CompilationUnit> usedModules = new HashSet<>();
//    usedModules.addAll(transformedModelResult.getModuleDependencies());
//
//    if (transformedModelResult.getSynthesizer() == null)
//      throw new IllegalArgumentException("Argument " + transformedModelResult + " must be a generated module with getSynthesizer() != null");
//    
//    Set<CompilationUnit> allowedModules = new HashSet<>(transformedModelResult.getSynthesizer().generatorModules);
//    for (CompilationUnit mod : transformedModelResult.getSynthesizer().generatorModules) {
//      allowedModules.addAll(mod.getModuleDependencies());
//    }
//    
//    Set<CompilationUnit> hiddenDependencies = new HashSet<>();
//    for (CompilationUnit m : usedModules)
//      if (!checkCommunicationIntegrityDepOK(m, allowedModules))
//        hiddenDependencies.add(m);
//    
//    if (!hiddenDependencies.isEmpty()) {
//      StringBuilder builder = new StringBuilder();
//      for (Iterator<CompilationUnit> it = hiddenDependencies.iterator(); it.hasNext(); ) {
//        builder.append(it.next().getName());
//        if (it.hasNext())
//          builder.append(", ");
//      }
//      
//      driver.setErrorMessage(toplevelDecl, "Generated model contains hidden dependencies " + builder.toString() + ".");
//    }
//  }
//  
//  private boolean checkCommunicationIntegrityDepOK(CompilationUnit m, Set<CompilationUnit> allowed) {
//    if (allowed.contains(m))
//      return true;
//    if (m.getSynthesizer() == null)
//      return false;
//    for (CompilationUnit m2 : m.getSynthesizer().generatorModules)
//      if (!checkCommunicationIntegrityDepOK(m2, allowed))
//        return false;
//    return true;
//  }

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
