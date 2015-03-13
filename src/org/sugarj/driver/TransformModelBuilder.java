package org.sugarj.driver;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.HybridInterpreter;
import org.sugarj.cleardep.build.Builder;
import org.sugarj.cleardep.build.BuilderFactory;
import org.sugarj.cleardep.stamp.FileHashStamper;
import org.sugarj.cleardep.stamp.Stamper;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;

public class TransformModelBuilder extends Builder<TransformModelBuilder.Input, IStrategoTerm> {

  public final static BuilderFactory<Input, IStrategoTerm, TransformModelBuilder> factory = new BuilderFactory<Input, IStrategoTerm, TransformModelBuilder>() {
    private static final long serialVersionUID = -2879215256932097082L;

    @Override
    public TransformModelBuilder makeBuilder(Input input) {
      return new TransformModelBuilder(input);
    }
  };
  
  public static class Input implements Serializable {
    private static final long serialVersionUID = -5434098091972595166L;
    public final RelativePath modelPath;
    public final DriverBuildRequest modelReq;
    public final RelativePath transformationPath;
    public final DriverBuildRequest transformationReq;
    public final IStrategoTerm toplevelDecl;
    public final RelativePath outputPath;
    public final STRCommands strCommands;
    public final Path baseLanguageDir;
    public final ImportCommands importCommands;
    public final HybridInterpreter strInterpreter;

    public Input(RelativePath modelPath, DriverBuildRequest modelReq, RelativePath transformationPath, DriverBuildRequest transformationReq, IStrategoTerm toplevelDecl, RelativePath outputPath, STRCommands strCommands, Path baseLanguageDir, ImportCommands importCommands, HybridInterpreter strInterpreter) {
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

  public TransformModelBuilder(Input input) {
    super(input);
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
  protected Stamper defaultStamper() {
    return FileHashStamper.instance;
  }

  @Override
  protected IStrategoTerm build() throws Throwable {
    requireBuild(input.modelReq);
    requireBuild(input.transformationReq);

    IStrategoTerm modelTerm = ATermCommands.atermFromFile(input.modelPath.getAbsolutePath());
    String modelName = FileCommands.dropExtension(input.modelPath.getRelativePath());
    String transName = FileCommands.dropExtension(input.transformationPath.getRelativePath());
    String strat = "main-" + transName.replace('/', '_');

    Path trans = input.strCommands.compile(input.transformationPath, ModuleSystemCommands.getTransitivelyAffectedFileStamps(getBuildUnit()).keySet(), input.baseLanguageDir);

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
    generate(input.outputPath);
    return renamedTransformedModel;
  }

  private IStrategoTerm renameModel(IStrategoTerm transformedModel, RelativePath modelPath, RelativePath transformedModelPath, Path compiledTrans, IStrategoTerm toplevelDecl) throws IOException {
    FromTo renaming = new FromTo(modelPath, transformedModelPath);
    IStrategoTerm map = Renaming.makeRenamingHashtable(Collections.singletonList(renaming));
    IStrategoTerm[] targs = new IStrategoTerm[] { map };
    return STRCommands.execute("apply-renamings", targs, compiledTrans, transformedModel, input.strInterpreter);
  }

}
