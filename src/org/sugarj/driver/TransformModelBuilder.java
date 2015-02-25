package org.sugarj.driver;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.HybridInterpreter;
import org.sugarj.cleardep.CompilationUnit;
import org.sugarj.cleardep.build.BuildManager;
import org.sugarj.cleardep.build.Builder;
import org.sugarj.cleardep.build.BuilderFactory;
import org.sugarj.cleardep.stamp.ContentHashStamper;
import org.sugarj.cleardep.stamp.Stamper;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;

public class TransformModelBuilder extends Builder<TransformModelBuilder.Input, CompilationUnit> {

  public final static BuilderFactory<Input, CompilationUnit, TransformModelBuilder> factory = new BuilderFactory<Input, CompilationUnit, TransformModelBuilder>() {
    private static final long serialVersionUID = -2879215256932097082L;

    @Override
    public TransformModelBuilder makeBuilder(Input input, BuildManager manager) {
      return new TransformModelBuilder(input, this, manager);
    }
  };
  
  public static class Input implements Serializable {
    private static final long serialVersionUID = -5434098091972595166L;
    public final RelativePath modelPath;
    public final DriverBuildRequirement modelReq;
    public final RelativePath transformationPath;
    public final DriverBuildRequirement transformationReq;
    public final IStrategoTerm toplevelDecl;
    public final RelativePath outputPath;
    public final STRCommands strCommands;
    public final Path baseLanguageDir;
    public final ImportCommands importCommands;
    public final HybridInterpreter strInterpreter;

    public Input(RelativePath modelPath, DriverBuildRequirement modelReq, RelativePath transformationPath, DriverBuildRequirement transformationReq, IStrategoTerm toplevelDecl, RelativePath outputPath, STRCommands strCommands, Path baseLanguageDir, ImportCommands importCommands, HybridInterpreter strInterpreter) {
      this.modelPath = modelPath;
      this.modelReq = modelReq;
      this.transformationPath = transformationPath;
      this.transformationReq = transformationReq;
      this.toplevelDecl = toplevelDecl;
      this.outputPath = outputPath;
      this.strCommands = strCommands;
      this.baseLanguageDir = baseLanguageDir;
      this.importCommands = importCommands;
      this.strInterpreter = strInterpreter;
    }
  }

  public TransformModelBuilder(Input input, BuilderFactory<Input, CompilationUnit, ? extends Builder<Input, CompilationUnit>> sourceFactory, BuildManager manager) {
    super(input, sourceFactory, manager);
  }

  @Override
  protected String taskDescription() {
    return "Transform model " + FileCommands.fileName(input.modelPath) + " with transformation " + FileCommands.fileName(input.transformationPath);
  }

  @Override
  protected Path persistentPath() {
    return FileCommands.addExtension(input.outputPath, "dep");
  }

  @Override
  protected Class<CompilationUnit> resultClass() {
    return CompilationUnit.class;
  }

  @Override
  protected Stamper defaultStamper() {
    return ContentHashStamper.instance;
  }

  @Override
  protected void build(CompilationUnit result) throws Throwable {
    require(input.modelReq);
    Result transformationResult = require(input.transformationReq);

    IStrategoTerm modelTerm = ATermCommands.atermFromFile(input.modelPath.getAbsolutePath());
    String modelName = FileCommands.dropExtension(input.modelPath.getRelativePath());
    String transName = FileCommands.dropExtension(input.transformationPath.getRelativePath());
    String strat = "main-" + transName.replace('/', '_');

    Path trans = input.strCommands.compile(input.transformationPath, transformationResult.getTransitivelyAffectedFiles(), input.baseLanguageDir);

    IStrategoTerm transformedTerm;
    try {
      input.importCommands.setCurrentModelName(modelName);
      input.importCommands.setCurrentTransName(transName);
      transformedTerm = STRCommands.execute(strat, trans, modelTerm, input.strInterpreter);
    } finally {
      input.importCommands.setCurrentModelName(null);
      input.importCommands.setCurrentTransName(null);
    }

    // local renaming of model name according to transformation
    IStrategoTerm renamedTransformedModel = renameModel(transformedTerm, input.modelPath, input.outputPath, trans, input.toplevelDecl);
    String transformedModelText = ATermCommands.atermToString(renamedTransformedModel);
    FileCommands.writeToFile(input.outputPath, transformedModelText);
    result.addGeneratedFile(input.outputPath);
  }

  private IStrategoTerm renameModel(IStrategoTerm transformedModel, RelativePath modelPath, RelativePath transformedModelPath, Path compiledTrans, IStrategoTerm toplevelDecl) throws IOException {
    FromTo renaming = new FromTo(modelPath, transformedModelPath);
    IStrategoTerm map = Renaming.makeRenamingHashtable(Collections.singletonList(renaming));
    IStrategoTerm[] targs = new IStrategoTerm[] { map };
    return STRCommands.execute("apply-renamings", targs, compiledTrans, transformedModel, input.strInterpreter);
  }

}
