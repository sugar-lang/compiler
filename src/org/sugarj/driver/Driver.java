package org.sugarj.driver;

import static org.sugarj.common.ATermCommands.fixSDF;
import static org.sugarj.common.ATermCommands.getApplicationSubterm;
import static org.sugarj.common.ATermCommands.isApplication;
import static org.sugarj.common.Log.log;
import static org.sugarj.driver.SDFCommands.extractSDF;
import static org.sugarj.driver.STRCommands.extractEditor;
import static org.sugarj.driver.STRCommands.extractSTR;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.ParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.spoofax.interpreter.terms.IStrategoAppl;
import org.spoofax.interpreter.terms.IStrategoList;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.ITreeBuilder;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.client.ParseTable;
import org.spoofax.jsglr.client.SGLR;
import org.spoofax.jsglr.client.imploder.ImploderAttachment;
import org.spoofax.jsglr.shared.BadTokenException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.spoofax.terms.Term;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.StrategoException;
import org.sugarj.AbstractBaseLanguage;
import org.sugarj.AbstractBaseProcessor;
import org.sugarj.cleardep.CompilationUnit;
import org.sugarj.cleardep.Mode;
import org.sugarj.cleardep.Synthesizer;
import org.sugarj.cleardep.stamp.Stamp;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.ATermCommands.PrettyPrintError;
import org.sugarj.common.CommandExecution;
import org.sugarj.common.Environment;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.StringCommands;
import org.sugarj.common.errors.SourceCodeException;
import org.sugarj.common.errors.SourceLocation;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Renaming.FromTo;
import org.sugarj.driver.caching.ModuleKeyCache;
import org.sugarj.driver.declprovider.ToplevelDeclarationProvider;
import org.sugarj.driver.transformations.primitive.SugarJPrimitivesLibrary;
import org.sugarj.stdlib.StdLib;
import org.sugarj.transformations.analysis.AnalysisDataInterop;
import org.sugarj.util.Pair;
import org.sugarj.util.ProcessingListener;


/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Driver {
  
  private final static int PENDING_TIMEOUT = 30000;

  private static Map<Set<? extends Path>, Entry<ToplevelDeclarationProvider, Driver>> pendingRuns = new HashMap<>();
  private static List<ProcessingListener> processingListener = new LinkedList<ProcessingListener>();

  /**
   * cache location -> cache
   */
  private static Map<Path, ModuleKeyCache<Path>> sdfCaches;
  private static Map<Path, ModuleKeyCache<Path>> strCaches;
  
  private ModuleKeyCache<Path> sdfCache;
  private ModuleKeyCache<Path> strCache;

  
  private Set<Set<RelativePath>> circularLinks = new HashSet<>();
  private boolean dependsOnModel = false;

  private DriverParameters params;
  
  private Result driverResult;
  private ImportCommands imp;

  private SDFCommands sdf;
  private Path currentGrammarSDF;
  private String currentGrammarModule;
  private Path currentGrammarTBL;
  private List<String> availableSDFImports;
  
  private STRCommands str;
  private Path currentTransSTR;
  private String currentTransModule;
  private Path currentTransProg;
  private List<String> availableSTRImports;
  
  private List<IStrategoTerm> sugaredBodyDecls = new ArrayList<IStrategoTerm>();
  private List<IStrategoTerm> desugaredBodyDecls = new ArrayList<IStrategoTerm>();
  
  private IStrategoTerm lastSugaredToplevelDecl;
  
  private SGLR parser;
  
  
  
  private boolean interrupt = false;
  
  private boolean inDesugaredDeclList;
  
  private AbstractBaseLanguage baseLanguage;
  private AbstractBaseProcessor baseProcessor;
  private boolean definesNonBaseDec = false;
  
  private AnalysisDataInterop analysisDataInterop;
  
  
  private static synchronized Entry<ToplevelDeclarationProvider, Driver> getPendingRun(Set<? extends Path> files) {
    return pendingRuns.get(files);
  }
  
  private static synchronized void putPendingRun(Set<? extends Path> files, ToplevelDeclarationProvider declProvider, Driver driver) {
    pendingRuns.put(files, new AbstractMap.SimpleImmutableEntry<ToplevelDeclarationProvider, Driver>(declProvider, driver));
  }
  
  public static synchronized void addProcessingDoneListener(ProcessingListener listener) {
    processingListener.add(listener);
  }
  
  public static synchronized void removeProcessingDoneListener(ProcessingListener listener) {
    processingListener.remove(listener);
  }
  
  private static void waitForPending(Set<? extends Path> files) {
    int count = 0;
    Object lock = new Object();
    synchronized (lock) {
      while (true) {
        synchronized (pendingRuns) {
          if (!pendingRuns.containsKey(files))
            return;
        }
        
        if (count > PENDING_TIMEOUT)
          throw new IllegalStateException("pending result timed out for " + files);
        
        count += 100;
        try {
          lock.wait(100);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  public static Result run(DriverParameters params) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Driver driver = new Driver();
    params.declProvider.setDriver(driver);
    return run(driver, params);
  }
  
  private static Result run(Driver driver, DriverParameters params) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Entry<ToplevelDeclarationProvider, Driver> pending = null;
    
    pending = getPendingRun(params.sourceFilePaths);
    if (pending != null && !pending.getKey().equals(params.declProvider) && pending.getValue().params.env.getMode() == driver.params.env.getMode()) {
      log.log("interrupting " + params.sourceFilePaths, Log.CORE);
      pending.getValue().interrupt();
    }

//    if (pending == null) {
//      Result result = ModuleSystemCommands.locateResult(modulePath, driver.environment);
//      
//      boolean isUpToDate = result != null && result.isConsistent();
//      if (isUpToDate) {
//        if (driver.environment.doGenerateFiles() && result.isParseResult()) {
//          Log.log.beginTask("Moving result", Log.DETAIL);
//          try {
//          result = result.moveTo(driver.environment.getBin(), false);
//          } finally {
//            Log.log.endTask();
//          }
//        }
//        
//        if (driver.environment.doGenerateFiles() || result.getSugaredSyntaxTree() != null)
//          return result;
//      }
//    }
    
    if (pending == null)
      putPendingRun(params.sourceFilePaths, params.declProvider, driver);
    else {
      waitForPending(params.sourceFilePaths);
      return run(driver, params);
    }
    
    try {
      ProcessingListener.notifyProcessingStarts(Driver.class, processingListener, params.sourceFilePaths);
    
      driver.initDriver(params);
      driver.initForSources(params);
      driver.process();
      
      Driver.storeCaches(driver.params.env);
    
      ProcessingListener.notifyProcessingDone(Driver.class, processingListener, driver.driverResult);
      
    } catch (InterruptedException e) {
      // nothing
    } catch (Exception e) {
      org.strategoxt.imp.runtime.Environment.logException(e);
    } finally {
      pendingRuns.remove(params.sourceFilePaths);
    }

    return driver.driverResult;
  }
  
  private void initDriver(DriverParameters params) throws FileNotFoundException, IOException, InvalidParseTableException {
    this.params = params;
    this.baseLanguage = params.baseLang;
    this.baseProcessor = baseLanguage.createNewProcessor();
    
    try {      
      if (params.env.getCacheDir() != null)
        FileCommands.createDir(params.env.getCacheDir());
      
      initializeCaches(params.env, false);
      sdfCache = selectCache(sdfCaches, baseLanguage, params.env);
      strCache = selectCache(strCaches, baseLanguage, params.env);
    } catch (IOException e) {
      throw new RuntimeException("error while initializing driver", e);
    }

    Path  baseLangPath = new AbsolutePath(baseLanguage.getPluginDirectory().getAbsolutePath());
    if (!params.env.getIncludePath().contains(baseLangPath))
      params.env.addToIncludePath(baseLangPath);
  
    baseProcessor.setInterpreter(new HybridInterpreter());
    HybridInterpreter interp = baseProcessor.getInterpreter();
    
    analysisDataInterop = new AnalysisDataInterop();
    analysisDataInterop.createInteropRegisterer().register(interp.getContext(), interp.getCompiledContext());
    
    currentGrammarSDF = baseLanguage.getInitGrammar();
    currentGrammarModule = baseLanguage.getInitGrammarModuleName();
    
    currentTransSTR = baseLanguage.getInitTrans();
    currentTransModule = baseLanguage.getInitTransModuleName();
    
    // list of imports that contain SDF extensions
    availableSDFImports = new ArrayList<String>();    
    availableSDFImports.add(baseLanguage.getInitGrammarModuleName());
  
    // list of imports that contain Stratego extensions
    availableSTRImports = new ArrayList<String>();
    availableSTRImports.add(baseLanguage.getInitTransModuleName());
  
    sdf = new SDFCommands(StdLib.sdfParser, sdfCache, params.env);
    str = new STRCommands(StdLib.strategoParser, strCache, params.env);
  }
  
  private void initForSources(DriverParameters params) throws IOException, TokenExpectedException, SGLRException, InterruptedException {
    if (params.sourceFilePaths.size() != 1) 
      throw new IllegalArgumentException("Cannot yet handle driver calls with more than one source file; FIXME");
    
    RelativePath sourceFile = getSourceFile();
    String depPath = FileCommands.dropExtension(sourceFile.getRelativePath()) + ".dep";
    Path dep = new RelativePath(params.env.getBin(), depPath);
    
    Stamp sourceFileStamp;
    if (params.editedSourceStamps.containsKey(sourceFile))
      sourceFileStamp = params.editedSourceStamps.get(sourceFile);
    else
      sourceFileStamp = params.env.getStamper().stampOf(sourceFile);
    
    driverResult = Result.create(params.env.getStamper(), params.env.<Result>getMode(), params.syn, dep);
    driverResult.addSourceArtifact(sourceFile, sourceFileStamp);
    
    imp = new ImportCommands(baseProcessor, params.env, this, params, driverResult, str);

    baseProcessor.init(params.sourceFilePaths, params.env);
    baseProcessor.getInterpreter().addOperatorRegistry(new SugarJPrimitivesLibrary(this, imp));
  }

  // FIXME there may be multiple source files
  public RelativePath getSourceFile() {
    if (params.sourceFilePaths.size() != 1)
      throw new RuntimeException("Can only support a single source file.");
    return params.sourceFilePaths.iterator().next();
  }
  
  /**
   * Process the given Extensible Java file.
   * 
   * @throws IOException 
   * @throws SGLRException 
   * @throws InvalidParseTableException 
   * @throws ParseException 
   * @throws TokenExpectedException 
   * @throws InterruptedException 
   */
  private void process() throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    List<FromTo> originalRenamings = new LinkedList<FromTo>(params.renamings);
    params.currentlyProcessing.add(this);
    
    log.beginTask("processing", "Process " + params.sourceFilePaths, Log.CORE);
    driverResult.setState(CompilationUnit.State.IN_PROGESS); 
    boolean success = false;
    try {
      boolean done = false;
      while (!done) {
        stepped();
        
        // PARSE the next top-level declaration
        lastSugaredToplevelDecl = params.declProvider.getNextToplevelDecl(true, false);
        
        stepped();
        
        // ANALYZE the parsed top-level declaration
        IStrategoTerm analyzed = currentAnalyze(lastSugaredToplevelDecl);
        
        stepped();
        
        // DESUGAR the analyzed top-level declaration
        IStrategoTerm desugared = currentDesugar(analyzed);
        
        stepped();
        
        // RENAME the desugared top-level declaration
        IStrategoTerm renamed = currentRename(desugared);
        
        stepped();
        
        // PROCESS the assimilated top-level declaration
        processToplevelDeclaration(renamed);

        done = !params.declProvider.hasNextToplevelDecl();
      }
      
      stepped();
            
      // check final grammar and transformation for errors
      if (!params.env.isNoChecking()) {
        checkCurrentGrammar();
      }
      
      stepped();
      
      // need to build current transformation program for editor services
      checkCurrentTransformation();
      
      stepped();
      
      // GENERATE model
      if (params.syn == null)
        generateModel();
      
      // COMPILE the generated java file
      if (circularLinks.isEmpty())
        compileGeneratedFiles();
      else {
        driverResult.generateFile(baseProcessor.getGeneratedSourceFile(), baseProcessor.getGeneratedSource());
        
        Result delegate = null;
        for (Driver dr : params.currentlyProcessing)
          if (circularLinks.contains(dr.params.sourceFilePaths)) {
            delegate = dr.driverResult;
            break;
          }
        if (delegate != null)
          driverResult.delegateCompilation(delegate, Collections.singleton(baseProcessor.getGeneratedSourceFile()), definesNonBaseDec);
        else if (!dependsOnModel)
          throw new IllegalStateException("Could not delegate compilation of circular dependency to other compiler instance.");
      }
        
      driverResult.setSugaredSyntaxTree(makeSugaredSyntaxTree());
      driverResult.setDesugaredSyntaxTree(makeDesugaredSyntaxTree());
      
      if (currentGrammarTBL != null)
        driverResult.registerParseTable(currentGrammarTBL);
      
      if (currentTransProg != null)
        driverResult.registerEditorDesugarings(currentTransProg);
      else
        driverResult.registerEditorDesugarings(StdLib.failureTrans);

      success = true;
    } 
    finally {
      log.endTask(success, "done processing " + params.sourceFilePaths, "failed to process " + params.sourceFilePaths);
      params.currentlyProcessing.remove(this);
      params.renamings.clear();
      params.renamings.addAll(originalRenamings);

      if (!interrupt) {
        driverResult.setState(success ? CompilationUnit.State.SUCCESS : CompilationUnit.State.FAILURE);
        driverResult.write(params.env.getStamper());
      } else {
        driverResult.setState(CompilationUnit.State.SUCCESS);
        driverResult.setSugaredSyntaxTree(null);
      }
    }
  }

  private void compileGeneratedFiles() throws IOException {
    boolean good = false;
    log.beginTask("compilation", "COMPILE generated " + baseProcessor.getLanguage().getLanguageName() + " files", Log.CORE);
    try {
      try {
        Set<Path> generatedFiles = 
          baseProcessor.compile(
              baseProcessor.getGeneratedSourceFile(), 
              baseProcessor.getGeneratedSource(),
              params.env.getBin(),
              driverResult,
              new ArrayList<Path>(params.env.getIncludePath()), 
              driverResult.getDeferredSourceFiles());
        for (Path file : generatedFiles)
          driverResult.addGeneratedFile(file);
      } catch (ClassNotFoundException e) {
        setErrorMessage("Could not resolve imported class " + e.getMessage());
      } catch (SourceCodeException e) {
        for (Pair<SourceLocation, String> err : e.getErrors())
          setErrorMessage(err.b + " lines " + err.a.lineStart + "-" + err.a.lineEnd
                                + " columns " + err.a.columnStart + "-" + err.a.columnEnd);
      }
      good = true;
    } finally {
      log.endTask(good);
    }
  }

  private void processToplevelDeclaration(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException {
    try {
      if (baseLanguage.isImportDecl(toplevelDecl) || baseLanguage.isTransformationImport(toplevelDecl)) {
        if (inDesugaredDeclList || !params.env.isAtomicImportParsing())
          processImportDec(toplevelDecl);
        else
          processImportDecs(toplevelDecl);
      } 
      else if (baseLanguage.isBaseDecl(toplevelDecl)) {
        List<String> additionalModules = processLanguageDec(toplevelDecl);
        for (String module : additionalModules) {
          prepareImport(toplevelDecl, module, null);
          Path clazz = ModuleSystemCommands.importBinFile(module, params.env, baseProcessor, driverResult);
          if (clazz == null)
            setErrorMessage(toplevelDecl, "Could not resolve required module " + module);
        }
      }
      else if (baseLanguage.isExtensionDecl(toplevelDecl))
        processExtensionDec(toplevelDecl);
      else if (baseLanguage.isPlainDecl(toplevelDecl)) 
        // XXX: Decide what to do with "Plain" -- leave in
        // the language or create a new "Plain" language
        processPlainDec(toplevelDecl);
      else if (baseLanguage.isTransformationDec(toplevelDecl))
        processTransformationDec(toplevelDecl);
      else if (baseLanguage.isModelDec(toplevelDecl))
        processModelDec(toplevelDecl);
      else if (baseLanguage.isExportDecl(toplevelDecl))
        processExportDec(toplevelDecl);
      else if (ATermCommands.isList(toplevelDecl)) {
        /*
         * Desugarings may generate lists of toplevel declarations.
         */
        List<IStrategoTerm> list = ATermCommands.getList(toplevelDecl);
        // sortForImports(list);

        boolean old = inDesugaredDeclList;
        inDesugaredDeclList = true;

        try {
          for (IStrategoTerm term : list)
            processToplevelDeclaration(term);
        } finally {
          inDesugaredDeclList = old;
        }
      } 
      else if (ATermCommands.isString(toplevelDecl)) {
        if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
          sugaredBodyDecls.add(lastSugaredToplevelDecl);
        if (!desugaredBodyDecls.contains(toplevelDecl))
          desugaredBodyDecls.add(toplevelDecl);

      } 
      else
        throw new IllegalArgumentException("unexpected toplevel declaration, desugaring probably failed: " + toplevelDecl.toString(20));
    } catch (Exception e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();

      if (!(e instanceof StrategoException))
        e.printStackTrace();

      setErrorMessage(lastSugaredToplevelDecl, msg);
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
    }
  }

  private void processEditorServices(String extName, IStrategoTerm services) throws IOException {
    if (!ATermCommands.isList(services))
      throw new IllegalStateException("editor services are not a list: " + services);
    
    RelativePath editorServicesFile = params.env.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".serv");
    List<IStrategoTerm> editorServices = ATermCommands.getList(services);
    
    log.log("writing editor services to " + editorServicesFile, Log.DETAIL);
    
    StringBuffer buf = new StringBuffer();
    
    for (IStrategoTerm service : driverResult.getEditorServices())
      buf.append(service).append('\n');
    
    for (IStrategoTerm service : editorServices) {
      driverResult.addEditorService(service);
      buf.append(service).append('\n');
    }
    
    driverResult.generateFile(editorServicesFile, buf.toString());
  }
  
  private void processPlainDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS plain declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);


      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "PlainDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "PlainDec", 1);
      
      String extName = ATermCommands.getString(getApplicationSubterm(head, "PlainDecHead", 1));
      checkModuleName(extName, toplevelDecl);

      String extension = null;
      if (head.getSubtermCount() >= 3 && isApplication(getApplicationSubterm(head, "PlainDecHead", 2), "Some"))
        extension = Term.asJavaString(getApplicationSubterm(getApplicationSubterm(head, "PlainDecHead", 2), "Some", 0));    

      String fullExtName = getFullRenamedDeclarationName(extName);
      fullExtName = fullExtName + (extension == null ? "" : ("." + extension));
      
      log.log("The name is '" + extName + "'.", Log.DETAIL);
      log.log("The full name is '" + fullExtName + "'.", Log.DETAIL);

      if (dependsOnModel)
        return;
      
      String plainContent = Term.asJavaString(ATermCommands.getApplicationSubterm(body, "PlainBody", 0));
      
      String ext = extension == null ? "" : ("." + extension);
      RelativePath plainFile = params.env.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ext);
      FileCommands.createFile(plainFile);

      log.log("writing plain content to " + plainFile, Log.DETAIL);
      driverResult.generateFile(plainFile, plainContent);
    } finally {
      log.endTask();
    }
  }
  
  
  public Pair<IStrategoTerm, Integer> currentParse(String remainingInput, ITreeBuilder treeBuilder, boolean recovery) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    
    currentGrammarTBL = sdf.compile(currentGrammarSDF, currentGrammarModule, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPackagedGrammars(), baseLanguage.getPluginDirectory());

    ParseTable table = ATermCommands.parseTableManager.loadFromFile(currentGrammarTBL.getAbsolutePath());
    
    Pair<SGLR, Pair<IStrategoTerm, Integer>> parseResult = null;

    // read next toplevel decl and stop if that fails
    try {
      parseResult = SDFCommands.parseImplode(
          table,
          remainingInput,
          StringCommands.printListSeparated(params.sourceFilePaths, "&"),
          "ToplevelDeclaration",
          recovery,
          true,
          treeBuilder);
//    } catch (SGLRException e) {
//      this.parser = e.getParser();
//      log.logErr(e.getMessage(), Log.DETAIL);
//      return null;
    } finally {
      if (parseResult != null)
        this.parser = parseResult.a;
      
      if (recovery && parser != null) {
        for (BadTokenException e : parser.getCollectedErrors())
          driverResult.logParseError(e);
      }
    }
    
    return parseResult.b;
  }

  private IStrategoTerm currentAnalyze(IStrategoTerm term) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
  // assimilate toplevelDec using current transformation
  
    log.beginTask("analyze", "ANALYZE toplevel declaration.", Log.CORE);
    try {
      currentTransProg = str.compile(currentTransSTR, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPluginDirectory());
    
      return STRCommands.execute("analyze-main", currentTransProg, term, baseProcessor.getInterpreter());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      
      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
      return term;
    } finally {
      log.endTask();
    }
  }

  private IStrategoTerm currentDesugar(IStrategoTerm term) throws IOException,
      InvalidParseTableException, TokenExpectedException, SGLRException {
    // assimilate toplevelDec using current transformation

    log.beginTask("desugaring", "DESUGAR toplevel declaration.", Log.CORE);
    try {
      String currentModelName = FileCommands.dropExtension(getSourceFile().getRelativePath());
      imp.setCurrentModelName(currentModelName);
      currentTransProg = str.compile(currentTransSTR, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPluginDirectory());

      return STRCommands.execute("internal-main", currentTransProg, term, baseProcessor.getInterpreter());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      
      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
      return term;
    } finally {
      imp.setCurrentModelName(null);
      log.endTask();
    }
  }

  /**
   * Apply current renamings stored in environment to the given term.
   */
  public IStrategoTerm currentRename(IStrategoTerm term) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    try {
      if (currentTransProg == null)
        return term;
      
      IStrategoTerm map = Renaming.makeRenamingHashtable(params.renamings);
      IStrategoTerm[] targs = new IStrategoTerm[] {map};
      IStrategoTerm result = STRCommands.execute("apply-renamings", targs, currentTransProg, term, baseProcessor.getInterpreter());
      return result == null ? term : result;
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();

      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
      return term;
    }
  }

  
  private void processImportDecs(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException {
    List<IStrategoTerm> pendingImports = new ArrayList<IStrategoTerm>();
    pendingImports.add(toplevelDecl);
    
    while (params.declProvider.hasNextToplevelDecl()) {
      IStrategoTerm term = null;
      
      try {
        log.beginSilent();
        term = params.declProvider.getNextToplevelDecl(false, true);
      }
      catch (Throwable t) {
        term = null;
      }
      finally {         
        log.endSilent(); 
      }
    
      if (term != null && (baseLanguage.isImportDecl(term) || baseLanguage.isTransformationImport(term)))
        pendingImports.add(term);
      else {
        params.declProvider.retract(term);
        break;
      }
    }
    
    for (IStrategoTerm pendingImport : pendingImports) {
      lastSugaredToplevelDecl = pendingImport;
      processImportDec(pendingImport);
    }
  }

  private void processImportDec(IStrategoTerm toplevelDecl) {
    
    if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
      sugaredBodyDecls.add(lastSugaredToplevelDecl);
    if (!desugaredBodyDecls.contains(toplevelDecl))
      desugaredBodyDecls.add(toplevelDecl);
    
    log.beginTask("processing", "PROCESS import declaration.", Log.CORE);
    try {
      Pair<String, Boolean> importResult = resolveImportDec(toplevelDecl);
      if (importResult == null)
        return ;
      
      IStrategoTerm reconstructedImport = baseProcessor.reconstructImport(importResult.a);
      if (reconstructedImport != null) {
        if (desugaredBodyDecls.remove(toplevelDecl))
          desugaredBodyDecls.add(reconstructedImport);
        toplevelDecl = reconstructedImport;
      }

      String modulePath = importResult.a;
      boolean isCircularImport = importResult.b;
      
      if (isCircularImport)
        return;
      boolean codeImportSuccess = processImport(modulePath, toplevelDecl);
      boolean modelImportSuccess = processModelImport(modulePath);
      if (modelImportSuccess && !codeImportSuccess)
        dependsOnModel = true;
      boolean success = codeImportSuccess || modelImportSuccess;
      
      if (!success)
        setErrorMessage("module not found: " + modulePath);
      
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      log.endTask();
    }
  }
  
  public Pair<String,Boolean> resolveImportDec(IStrategoTerm toplevelDecl) throws TokenExpectedException, ClassNotFoundException, IOException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    if (!baseLanguage.isTransformationImport(toplevelDecl)) {
      String modulePath = baseProcessor.getModulePathOfImport(toplevelDecl);
      
      boolean isCircularImport = prepareImport(toplevelDecl, modulePath, null);
      
      String localModelName = baseProcessor.getImportLocalName(toplevelDecl);
      
      if (localModelName != null)
        params.renamings.add(0, new FromTo(Collections.<String>emptyList(), localModelName, FileCommands.fileName(modulePath)));
      
      return Pair.create(modulePath, isCircularImport);
    } 
    else {
      IStrategoTerm appl = baseLanguage.getTransformationApplication(toplevelDecl);
      
      Pair<RelativePath, Boolean> transformationResult = imp.resolveModule(appl, true);
      
      if (transformationResult == null)
        return null;
      
      String modulePath = FileCommands.dropExtension(transformationResult.a.getRelativePath());
      String localModelName = baseProcessor.getImportLocalName(toplevelDecl);
      
      if (localModelName != null)
        params.renamings.add(0, new FromTo(Collections.<String>emptyList(), localModelName, FileCommands.fileName(transformationResult.a)));
      else
        params.renamings.add(0, new FromTo(ImportCommands.getTransformationApplicationModelPath(appl, baseProcessor), modulePath));
      
      return Pair.create(modulePath, transformationResult.b);
    }    
  }

  /**
   * Prepare import:
   *  - locate pre-existing result and/or source file
   *  - determine whether the import is circular
   *  - initiate subcompilation of imported source file if necessary
   *  - add appropriate dependencies to driverResult 
   * 
   * @param toplevelDecl
   * @param modulePath
   * @return true iff the import is circular.
   * @throws IOException
   * @throws InterruptedException 
   * @throws SGLRException 
   * @throws InvalidParseTableException 
   * @throws ParseException 
   * @throws TokenExpectedException 
   * @throws ClassNotFoundException 
   */
  protected boolean prepareImport(IStrategoTerm toplevelDecl, String modulePath, Synthesizer syn) throws IOException, TokenExpectedException, ParseException, InvalidParseTableException, SGLRException, InterruptedException, ClassNotFoundException {
    // module is in sugarj standard library
    if (modulePath.startsWith("org/sugarj"))
      return false;
    
    Mode<Result> reqmode = params.env.<Result>getMode().getModeForRequiredModules();
    Result result = ModuleSystemCommands.locateConsistentResult(modulePath, params.env, reqmode, params.editedSourceStamps);
    boolean consistent;
    if (result != null) {
      consistent = true;
    }
    else {
      result = ModuleSystemCommands.locateResult(modulePath, params.env, reqmode);
      consistent = false;
    }
    
    Set<RelativePath> importSourceFiles;
    if (result != null && !result.getSourceArtifacts().isEmpty())
      importSourceFiles = result.getSourceArtifacts();
    else {
      importSourceFiles = new HashSet<>();
      RelativePath importSourceFile = ModuleSystemCommands.locateSourceFileOrModel(modulePath, params.env.getSourcePath(), baseProcessor, params.env);
      if (importSourceFile != null)
        importSourceFiles.add(importSourceFile);
    }

    boolean sourceFileAvailable = !importSourceFiles.isEmpty();
    boolean requiresUpdate = result == null || !consistent;
    
    Result circularResult = getCircularImportResult(importSourceFiles);
    if (circularResult != null) {
      // Circular import. Assume source file does not provide syntactic sugar.
      log.log("Circular import detected: " + modulePath + ".", Log.IMPORT);
      baseProcessor.processModuleImport(toplevelDecl);
      circularLinks.add(importSourceFiles);
      driverResult.addCircularModuleDependency(circularResult);
      return true;
    }
    
    if (sourceFileAvailable && requiresUpdate) {
      // Required module needs recompilation.
      log.log("Need to compile imported module " + modulePath + " first.", Log.IMPORT);
      
      // FIXME
      assert importSourceFiles.size() == 1 : "Cannot yet pass multiple source files as input to compiler, need's fixing.";
      
      if (syn == null && result != null)
        syn = result.getSynthesizer();
      result = subcompile(importSourceFiles.iterator().next(), syn);
      if (result == null || result.hasFailed())
        setErrorMessage("Problems while compiling " + modulePath);
        
      log.log("CONTINUE PROCESSING'" + params.sourceFilePaths + "'.", Log.CORE);
    }
    
    if (result != null) {
      if (result.hasPersistentVersionChanged())
        setErrorMessage("Result is inconsitent with persistent version.");
      driverResult.addModuleDependency(result);
    }
    
    if (!importSourceFiles.isEmpty())
      // if importSourceFile is delegated to something currently being processed
      for (Driver dr : params.currentlyProcessing)
        if (dr.driverResult.isDelegateOf(importSourceFiles)) {
          baseProcessor.processModuleImport(toplevelDecl);

          if (dr != this)
            circularLinks.add(dr.params.sourceFilePaths);
          
          return true;
        }
    
    return false;
  }
  
  /**
   * Checks if the given source file is a circular import.
   * Checks the ongoing driver runs to determine whether the source file in turn imports the current source file.
   * 
   * @return null if the import is not circular. The path to the imported file's driver result otherwise.
   */
  private Result getCircularImportResult(Set<RelativePath> importSourceFiles) {
    for (Driver dr : params.currentlyProcessing)
      if (!Collections.disjoint(dr.params.sourceFilePaths, importSourceFiles))
        return dr.driverResult;
    
    return null;
  }

  /**
   * Subcompile source file.
   * @param toplevelDecl
   * @param importSourceFile
   * @return
   * @throws InterruptedException
   */
  public Result subcompile(RelativePath importSourceFile, Synthesizer syn) throws InterruptedException {
    try {
      Result result;
      if ("model".equals(FileCommands.getExtension(importSourceFile))) {
        IStrategoTerm term = ATermCommands.atermFromFile(importSourceFile.getAbsolutePath());
        result = run(DriverParameters.create(params.env, baseLanguage, importSourceFile, term, params.editedSources, params.editedSourceStamps, params.currentlyProcessing, params.renamings, params.monitor, syn));
      }
      else
        result = run(DriverParameters.create(params.env, baseLanguage, importSourceFile, params.editedSources, params.editedSourceStamps, params.currentlyProcessing, params.renamings, params.monitor, syn));
      return result;
    } catch (IOException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (TokenExpectedException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (ParseException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (InvalidParseTableException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    } catch (SGLRException e) {
      setErrorMessage("Problems while compiling " + importSourceFile);
    }
    return null;
  }
  
  private boolean processImport(String modulePath, IStrategoTerm importTerm) throws IOException {
    boolean success = false;
    
    Path clazz = ModuleSystemCommands.importBinFile(modulePath, params.env, baseProcessor, driverResult);
    if (clazz != null || baseProcessor.isModuleExternallyResolvable(modulePath)) {
      success = true;
      baseProcessor.processModuleImport(importTerm);
    }

    Path sdf = ModuleSystemCommands.importSdf(modulePath, params.env, driverResult);
    if (sdf != null) {
      success = true;
      availableSDFImports.add(modulePath);
      buildCompoundSdfModule();
    }
    
    Path str = ModuleSystemCommands.importStratego(modulePath, params.env, driverResult);
    if (str != null) {
      success = true;
      availableSTRImports.add(modulePath);
      buildCompoundStrModule();
    }
    
    success |= ModuleSystemCommands.importEditorServices(modulePath, params.env, driverResult);
    
    return success;
  }
  
  private boolean processModelImport(String modulePath) throws IOException {
    RelativePath model = ModuleSystemCommands.importModel(modulePath, params.env, driverResult);
    if (model != null) {
//      availableModels.add(model);
      return true;
    }
    
    return false;
  }
  
  private void processExportDec(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, ClassNotFoundException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
      sugaredBodyDecls.add(lastSugaredToplevelDecl);
    if (!desugaredBodyDecls.contains(toplevelDecl))
      desugaredBodyDecls.add(toplevelDecl);
    
    IStrategoTerm otherModule = baseProcessor.getImportForExport(toplevelDecl);
    
    Pair<String, Boolean> importModelResult = resolveImportDec(otherModule);
    if (importModelResult == null) {
      setErrorMessage(toplevelDecl, "Could not resolve module for export: " + otherModule);
      return ;
    }
    if (importModelResult.b) {
      setErrorMessage(toplevelDecl, "Export is cyclic: " + otherModule);
      return ;
    }
    
    RelativePath importModelPath = ModuleSystemCommands.importModel(importModelResult.a, params.env, driverResult);
    if (importModelPath == null) {
      setErrorMessage(toplevelDecl, "Cannot locate generated model: " + importModelResult.a);
      return ;
    }

    String thisModuleName = baseProcessor.getRelativeNamespaceSep() + baseLanguage.getExportName(toplevelDecl);
    RelativePath thisModelPath = params.env.createOutPath(thisModuleName + ".model");

    IStrategoTerm importModel = ATermCommands.atermFromFile(importModelPath.getAbsolutePath());
    FromTo renaming = new FromTo(importModelPath, thisModelPath);
    IStrategoTerm thisModel = imp.renameModel(importModel, renaming, currentTransProg, toplevelDecl, importModelPath.getAbsolutePath());
    ATermCommands.atermToFile(thisModel, thisModelPath);

    CompilationUnit.State state = driverResult.getState();
    Set<CompilationUnit> dependencies = new HashSet<>(driverResult.getModuleDependencies());
    Set<CompilationUnit> circularDepdencnies = new HashSet<>(driverResult.getCircularModuleDependencies());
    Set<Path> generatedFiles = new HashSet<>(driverResult.getGeneratedFiles());
    Set<Path> externalFileDependencies = new HashSet<>(driverResult.getExternalFileDependencies());
    Set<RelativePath> sourceFiles = driverResult.getSourceArtifacts();

    // TODO Declare a synthesizer?
    Result modelResult = subcompile(thisModelPath, null);
    
    if (modelResult.hasFailed())
      state = CompilationUnit.State.FAILURE;
    dependencies.addAll(modelResult.getModuleDependencies());
    circularDepdencnies.addAll(modelResult.getCircularModuleDependencies());
    generatedFiles.addAll(modelResult.getGeneratedFiles());
    externalFileDependencies.addAll(modelResult.getExternalFileDependencies()); 
    
    RelativePath sourceFile1 = getSourceFile();
    String depPath = FileCommands.dropExtension(sourceFile1.getRelativePath()) + ".dep";
    Path dep = new RelativePath(params.env.getBin(), depPath);
    this.driverResult = Result.create(params.env.getStamper(), params.env.<Result>getMode(), params.syn, dep);
    
    driverResult.setState(state);
    for (RelativePath sourceFile : sourceFiles)
      driverResult.addSourceArtifact(sourceFile);
    for (CompilationUnit cu : dependencies)
      driverResult.addModuleDependency(cu);
    for (CompilationUnit cu : circularDepdencnies)
      driverResult.addModuleDependency(cu);
    for (Path p : generatedFiles)
      driverResult.addGeneratedFile(p);
    for (Path p : externalFileDependencies)
      driverResult.addExternalFileDependency(p);
    
    generateModel();
  }

  private List<String> processLanguageDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS " + baseProcessor.getLanguage().getLanguageName() + " declaration: " + ((toplevelDecl instanceof IStrategoAppl) ? ((IStrategoAppl) toplevelDecl).getName() : toplevelDecl.toString(0)), Log.CORE);
    try {
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);
      
      if (dependsOnModel)
        return Collections.emptyList();
      
      return baseProcessor.processBaseDecl(toplevelDecl);
    } finally {
      log.endTask();
    }
  }

  private void processExtensionDec(IStrategoTerm toplevelDecl) throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    log.beginTask("processing", "PROCESS sugar declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);

      String extName = baseProcessor.getExtensionName(toplevelDecl);
      String fullExtName = getFullRenamedDeclarationName(extName);
      checkModuleName(extName, toplevelDecl);

      log.log("The name of the sugar is '" + extName + "'.", Log.DETAIL);
      log.log("The full name of the sugar is '" + fullExtName + "'.", Log.DETAIL);
      
      if (dependsOnModel)
        return;
      
      RelativePath sdfExtension = params.env.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".sdf");
      RelativePath strExtension = params.env.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".str");
      
      String sdfImports = " imports " + StringCommands.printListSeparated(availableSDFImports, " ") + "\n";
      String strImports = " imports " + StringCommands.printListSeparated(availableSTRImports, " ") + "\n";
      
      // this is a list of SDF and Stratego statements
      
      IStrategoTerm extensionBody = baseProcessor.getExtensionBody(toplevelDecl);

      IStrategoTerm sdfExtract = fixSDF(extractSDF(extensionBody), baseProcessor.getInterpreter());
      IStrategoTerm strExtract = extractSTR(extensionBody);
      IStrategoTerm editorExtract = extractEditor(extensionBody);
      
      String sdfExtensionHead =
        "module " + fullExtName + "\n" 
        + sdfImports
        + "exports " + "\n"
        + "  (/)" + "\n";

      String sdfExtensionContent = SDFCommands.prettyPrintSDF(sdfExtract, baseProcessor.getInterpreter());

      String sdfSource = SDFCommands.makePermissiveSdf(sdfExtensionHead + sdfExtensionContent);
      driverResult.generateFile(sdfExtension, sdfSource);
      if (!availableSDFImports.contains(fullExtName))
        availableSDFImports.add(fullExtName);
      
      if (CommandExecution.FULL_COMMAND_LINE)
        log.log("Wrote SDF file to '" + sdfExtension.getAbsolutePath() + "'.", Log.DETAIL);
      
      String strExtensionTerm = "Module(" + "\"" + fullExtName+ "\"" + ", " + strExtract + ")" + "\n";
//      try {
//        strExtensionTerm = STRCommands.assimilate("strip-annos", currentTransProg, strExtensionTerm, langLib.getInterpreter());
//      } catch (Exception e) {
//        e.printStackTrace();
//      }
//      String strExtensionContent = SDFCommands.prettyPrintSTR(strExtensionTerm, langLib.getInterpreter());
      String strExtensionContent = SDFCommands.prettyPrintSTR(ATermCommands.atermFromString(strExtensionTerm), baseProcessor.getInterpreter());
      
      int index = strExtensionContent.indexOf('\n');
      if (index >= 0)
        strExtensionContent =
          strExtensionContent.substring(0, index + 1) + "\n"
          + strImports + "\n"
          + strExtensionContent.substring(index + 1);
      else
        strExtensionContent += strImports;
        
      
      driverResult.generateFile(strExtension, strExtensionContent);
      if (!availableSTRImports.contains(fullExtName))
        availableSTRImports.add(fullExtName);

      if (CommandExecution.FULL_COMMAND_LINE)
        log.log("Wrote Stratego file to '" + strExtension.getAbsolutePath() + "'.", Log.DETAIL);
      
      processEditorServices(extName, editorExtract);
      
      /*
       * adapt current grammar
       */
      if (FileCommands.exists(sdfExtension)) {
        buildCompoundSdfModule();
      }

      /*
       * adapt current transformation
       */
      if (FileCommands.exists(strExtension))
        buildCompoundStrModule();

    } catch (PrettyPrintError e) {
      setErrorMessage(e.getMsg());
    } finally {
      log.endTask();
    }
  }
  
  private void processTransformationDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS transformation declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);

      String extName = baseLanguage.getTransformationName(toplevelDecl);
      String fullExtName = getFullRenamedDeclarationName(extName);
      checkModuleName(extName, toplevelDecl);
      
      RelativePath strExtension = params.env.createOutPath(baseProcessor.getRelativeNamespaceSep() + extName + ".str");
      IStrategoTerm transBody = baseLanguage.getTransformationBody(toplevelDecl);
      if (isApplication(transBody, "TransformationDef")) 
        transBody = ATermCommands.factory.makeListCons(ATermCommands.makeAppl("Rules", "Rules", 1, transBody.getSubterm(0)), (IStrategoList) transBody.getSubterm(1));
      
      log.log("The name of the transformation is '" + extName + "'.", Log.DETAIL);
      log.log("The full name of the transformation is '" + fullExtName + "'.", Log.DETAIL);
      
      if (dependsOnModel)
        return;
      
      String qualifiedMain = "main-" + fullExtName.replace('/', '_');
      IStrategoTerm renamedTransBody = STRCommands.renameRules(transBody, "main", qualifiedMain);
      
      String strImports = " imports " + StringCommands.printListSeparated(availableSTRImports, " ") + "\n";
      String strExtensionTerm = "Module(" + "\"" + fullExtName+ "\"" + ", " + renamedTransBody + ")" + "\n";
      String strExtensionContent = SDFCommands.prettyPrintSTR(ATermCommands.atermFromString(strExtensionTerm), baseProcessor.getInterpreter());
      
      int index = strExtensionContent.indexOf('\n');
      if (index >= 0)
        strExtensionContent =
          strExtensionContent.substring(0, index + 1) + "\n"
          + strImports + "\n"
          + strExtensionContent.substring(index + 1);
      else
        strExtensionContent += strImports;
            
      driverResult.generateFile(strExtension, strExtensionContent);
      availableSTRImports.add(fullExtName);
      
      log.log("Wrote Stratego file to '" + strExtension.getAbsolutePath() + "'.", Log.DETAIL);
      
      /*
       * adapt current transformation
       */
      if (FileCommands.exists(strExtension))
        buildCompoundStrModule();

    } finally {
      log.endTask();
    }
  }
  
  private String getFullRenamedDeclarationName(String declName) {
    String fullExtName = baseProcessor.getRelativeNamespaceSep() + declName;
    
//    for (Renaming ren : environment.getRenamings())
//      fullExtName = StringCommands.rename(fullExtName, ren);

//    fullExtName = fullExtName.replace("$", "-");
    return fullExtName;
  }
  
  private void processModelDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS model declaration.", Log.CORE);
    try {
      definesNonBaseDec = true;
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      if (!desugaredBodyDecls.contains(toplevelDecl))
        desugaredBodyDecls.add(toplevelDecl);
  
      String modelName = baseLanguage.getModelName(toplevelDecl);
//      String fullModelName = getFullRenamedDeclarationName(modelName);
      checkModuleName(modelName, toplevelDecl);
  
      log.log("The name of the model is '" + modelName + "'.", Log.DETAIL);
//      checkToplevelDeclarationName(modelName.replace("-", "$"), "model", toplevelDecl);
    } finally {
      log.endTask();
    }
  }
  
  private void generateModel() throws IOException {
    log.beginTask("Generate model.", Log.DETAIL);
    try {
      String moduleName = FileCommands.dropExtension(getSourceFile().getRelativePath());
      RelativePath modelOutFile = params.env.createOutPath(moduleName + ".model");
      
      IStrategoTerm modelTerm = makeDesugaredSyntaxTree();
      String string = ATermCommands.atermToString(modelTerm);
      driverResult.generateFile(modelOutFile, string);
      
      if (params.sourceFilePaths.contains(modelOutFile))
        driverResult.addGeneratedFile(modelOutFile);
    } finally {
      log.endTask();
    }
  }
  
  private void buildCompoundSdfModule() throws IOException {
    FileCommands.deleteTempFiles(currentGrammarSDF);
    currentGrammarSDF = FileCommands.newTempFile("sdf");
    currentGrammarModule = FileCommands.fileName(currentGrammarSDF);
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(currentGrammarModule).append("\n");
    builder.append("imports ");
    for (String m : availableSDFImports)
      builder.append(m).append(" ");
    
    FileCommands.writeToFile(currentGrammarSDF, builder.toString());
  }
  
  private void buildCompoundStrModule() throws IOException {
    FileCommands.deleteTempFiles(currentTransSTR);
    currentTransSTR = FileCommands.newTempFile("str");
    currentTransModule = FileCommands.fileName(currentTransSTR);
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(currentTransModule).append("\n");
    builder.append("imports ");
    for (String m : availableSTRImports)
      builder.append(m).append(" ");
    
    FileCommands.writeToFile(currentTransSTR, builder.toString());
  }

  private void checkCurrentGrammar() throws IOException, InvalidParseTableException, TokenExpectedException, SGLRException {
    log.beginTask("checking grammar", "CHECK current grammar", Log.CORE);
    
    try {
      sdf.compile(currentGrammarSDF, currentGrammarModule, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPackagedGrammars(), baseLanguage.getPluginDirectory());
    } finally {
      log.endTask();
    }
  }
  
  private void checkCurrentTransformation() throws TokenExpectedException, IOException, InvalidParseTableException, SGLRException {
    log.beginTask("checking transformation", "CHECK current transformation", Log.CORE);
    
    try {
      currentTransProg = str.compile(currentTransSTR, driverResult.getTransitivelyAffectedFiles(), baseLanguage.getPluginDirectory());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      log.logErr(msg, Log.DETAIL);
      setErrorMessage(msg);
    } finally {
      log.endTask();
    }
  }
    
  private void checkModuleName(String decName, IStrategoTerm toplevelDecl) {
    String expectedDecName = FileCommands.fileName(baseProcessor.getGeneratedSourceFile());
    if (expectedDecName != null && !expectedDecName.equals(decName))
      setErrorMessage(lastSugaredToplevelDecl, "Declaration name " + decName + " does not match file name " + expectedDecName);
  }

  @SuppressWarnings("unchecked")
  private static synchronized void initializeCaches(Environment environment, boolean force) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path stdlibVersion = environment.createCachePath("version");
    if (!stdlibVersion.getFile().exists() || !FileCommands.readFileAsString(stdlibVersion).equals(StdLib.VERSION)) {
      for (File f : environment.getCacheDir().getFile().listFiles())
        f.delete();
      FileCommands.writeToFile(stdlibVersion, StdLib.VERSION);
    }
    
    Path sdfCachePath = environment.createCachePath("sdfCaches");
    Path strCachePath = environment.createCachePath("strCaches");
    
    if (sdfCaches == null || force)
      sdfCaches = new HashMap<Path, ModuleKeyCache<Path>>();
    if (strCaches == null || force)
      strCaches = new HashMap<Path, ModuleKeyCache<Path>>();
    
    if (!sdfCaches.containsKey(environment.getCacheDir()) || force) {
      ObjectInputStream sdfIn = null;
      try {
        sdfIn = new ObjectInputStream(new FileInputStream(sdfCachePath.getFile()));
        if (!sdfCaches.containsKey(environment.getCacheDir())) {
          ModuleKeyCache<Path> sdfLocalCaches = (ModuleKeyCache<Path>) sdfIn.readObject();
          sdfCaches.put(environment.getCacheDir(), sdfLocalCaches);
        }
      } catch (Exception e) {
//        e.printStackTrace();
        sdfCaches.put(environment.getCacheDir(), new ModuleKeyCache<Path>(sdfCaches));
      } finally {
        if (sdfIn != null)
          sdfIn.close();
      }
    }
    
    if (!strCaches.containsKey(environment.getCacheDir()) || force) {
      ObjectInputStream strIn = null;
      try {
        strIn = new ObjectInputStream(new FileInputStream(strCachePath.getFile()));
        if (!strCaches.containsKey(environment.getCacheDir())) {
          ModuleKeyCache<Path> strLocalCaches = (ModuleKeyCache<Path>) strIn.readObject();
          strCaches.put(environment.getCacheDir(), strLocalCaches);
        }
      } catch (Exception e) {
//        e.printStackTrace();
        strCaches.put(environment.getCacheDir(), new ModuleKeyCache<Path>(strCaches));
      } finally {
        if (strIn != null)
          strIn.close();
      }
    }
  }

  private static ModuleKeyCache<Path> selectCache(Map<Path, ModuleKeyCache<Path>> caches, AbstractBaseLanguage baseLang, Environment environment) throws IOException {
    if (caches == null)
      return null;
    synchronized (caches) {
      ModuleKeyCache<Path> cache = caches.get(environment.getCacheDir());
      Path versionPath = environment.createCachePath(baseLang.getLanguageName() + ".version");
      if (cache != null &&
          (!FileCommands.exists(versionPath) || !baseLang.getVersion().equals(FileCommands.readFileAsString(versionPath))))
        cache = null;
      if (cache == null) {
        cache = new ModuleKeyCache<Path>(caches);
        FileCommands.writeToFile(versionPath, baseLang.getVersion());
        caches.put(environment.getCacheDir(), cache);
      }
      return cache;
    }
  }
  
//TODO is this needed?
//  private static ModuleKeyCache<Path> reallocate(ModuleKeyCache<Path> cache, Environment env) {
//    ModuleKeyCache<Path> res = new ModuleKeyCache<Path>();
//    
//    for (Entry<ModuleKey, Path> e : cache.entrySet()) {
//      Map<Path, Integer> imports = new HashMap<Path, Integer>();
//      for (Entry<Path, Integer> e2 : e.getKey().imports.entrySet())
//        imports.put(Path.reallocate(e2.getKey(), env), e2.getValue());
//      
//      res.put(new ModuleKey(imports, e.getKey().body), Path.reallocate(e.getValue(), env));
//    }
//    
//    return res;
//  }


  private static synchronized void storeCaches(Environment environment) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path cacheVersion = environment.createCachePath("version");
    FileCommands.writeToFile(cacheVersion, StdLib.VERSION);
    
    Path sdfCachePath = environment.createCachePath("sdfCaches");
    Path strCachePath = environment.createCachePath("strCaches");

    if (!sdfCachePath.getFile().exists())
      FileCommands.createFile(sdfCachePath);

    if (!strCachePath.getFile().exists())
      FileCommands.createFile(strCachePath);
    
    if (sdfCaches != null) {
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(sdfCachePath.getFile()));
      try {
        oos.writeObject(sdfCaches.get(environment.getCacheDir()));
      } finally {
        oos.close();
      }
    }
    
    if (strCaches != null) {
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(strCachePath.getFile()));
      try {
        oos.writeObject(strCaches.get(environment.getCacheDir()));
      } finally {
        oos.close();
      }
    }
  }


  
  /**
   * @return the non-desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeSugaredSyntaxTree() {
    IStrategoTerm decls = ATermCommands.makeList("Decl*", params.declProvider.getStartToken(), sugaredBodyDecls);
    IStrategoTerm term = ATermCommands.makeAppl("CompilationUnit", "CompilationUnit", 1, decls);
    
    if (ImploderAttachment.getTokenizer(term) != null) {
      ImploderAttachment.getTokenizer(term).setAst(term);
      ImploderAttachment.getTokenizer(term).initAstNodeBinding();
    }
    
    analysisDataInterop.storeAnalysisData(term);
    
    return term;
  }
  
  /**
   * @return the desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeDesugaredSyntaxTree() {
    IStrategoTerm decls = ATermCommands.makeList("Decl*", params.declProvider.getStartToken(), desugaredBodyDecls);
    IStrategoTerm term = ATermCommands.makeAppl("CompilationUnit", "CompilationUnit", 1, decls);
        
    return term;
  }

  
  public synchronized void interrupt() {
    this.interrupt = true;
  }
  
  private synchronized void stopIfInterrupted() throws InterruptedException {
    if (interrupt || params.monitor.isCanceled()) {
      params.monitor.setCanceled(true);
      log.log("interrupted " + params.sourceFilePaths, Log.CORE);
      throw new InterruptedException("Compilation interrupted");
    }
  }

  private void stepped() throws InterruptedException {
    stopIfInterrupted();
    params.monitor.worked(1);
  }
  
  // FIXME 
//  private void clearGeneratedStuff() throws IOException {    
//    if (driverResult.getGenerationLog() != null && FileCommands.exists(driverResult.getGenerationLog())) {
//
//      ObjectInputStream ois = null;
//      
//      try {
//        ois = new ObjectInputStream(new FileInputStream(driverResult.getGenerationLog().getFile()));
//        while (true) {
//          try {
//            Path p = (Path) ois.readObject();
//            FileCommands.delete(p);
//          } catch (ClassNotFoundException e) { 
//          }
//        }
//      } catch (EOFException e) {
//      } catch (Exception e) {
//        e.printStackTrace();
//      } finally {
//        if (ois != null)
//          ois.close();
//        FileCommands.delete(driverResult.getGenerationLog());
//      }
//    }
//  }
  
  public void setErrorMessage(IStrategoTerm toplevelDecl, String msg) {
    driverResult.logError(msg);
    log.logErr(msg, Log.CORE);
    ATermCommands.setErrorMessage(toplevelDecl, msg);
  }

  public void setErrorMessage(String msg) {
    setErrorMessage(lastSugaredToplevelDecl, msg);
  }
  
  public AbstractBaseProcessor getBaseLanguage() {
    return baseProcessor;
  }
  
  public String getModuleName() {
    return FileCommands.fileName(getSourceFile());
  }
  
  public SGLR getParser() {
    return parser;
  }

  public IStrategoTerm getTreeForErrorMarking() {
    return lastSugaredToplevelDecl;
  }
  
  public Result getCurrentResult() {
    return driverResult;
  }
  
  public Environment getEnvironment() {
    return params.env;
  }
  
  public DriverParameters getParameters() {
    return params;
  }
  
  @Override
  public String toString() {
    return "Driver(" + params.sourceFilePaths + ")";
  }
}
