package org.sugarj.driver;

import static org.sugarj.common.ATermCommands.fixSDF;
import static org.sugarj.common.ATermCommands.getApplicationSubterm;
import static org.sugarj.common.ATermCommands.getList;
import static org.sugarj.common.ATermCommands.getString;
import static org.sugarj.common.ATermCommands.isApplication;
import static org.sugarj.common.Log.log;
import static org.sugarj.driver.SDFCommands.extractSDF;
import static org.sugarj.driver.STRCommands.extractSTR;

import java.io.EOFException;
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr_layout.client.InvalidParseTableException;
import org.spoofax.jsglr_layout.client.ParseTable;
import org.spoofax.jsglr_layout.client.SGLR;
import org.spoofax.jsglr_layout.client.imploder.IToken;
import org.spoofax.jsglr_layout.client.imploder.ImploderAttachment;
import org.spoofax.jsglr_layout.client.imploder.TreeBuilder;
import org.spoofax.jsglr_layout.shared.BadTokenException;
import org.spoofax.jsglr_layout.shared.SGLRException;
import org.spoofax.jsglr_layout.shared.TokenExpectedException;
import org.spoofax.terms.Term;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.StrategoException;
import org.strategoxt.permissivegrammars.make_permissive;
import org.strategoxt.tools.tools;
import org.sugarj.LanguageLib;
import org.sugarj.LanguageLibFactory;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.CommandExecution;
import org.sugarj.common.Environment;
import org.sugarj.common.FileCommands;
import org.sugarj.common.Log;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.common.path.RelativeSourceLocationPath;
import org.sugarj.common.path.SourceLocation;
import org.sugarj.driver.caching.ModuleKey;
import org.sugarj.driver.caching.ModuleKeyCache;
import org.sugarj.driver.cli.CLIError;
import org.sugarj.driver.cli.DriverCLI;
import org.sugarj.driver.transformations.extraction.extraction;
import org.sugarj.stdlib.StdLib;
import org.sugarj.util.Pair;
import org.sugarj.util.ProcessingListener;


/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Driver{
  
  public final static String CACHE_VERSION = "prologlib09";
  
  private final static int PENDING_TIMEOUT = 30000;

  private static Map<Path, Result> resultCache = new HashMap<Path, Result>(); // new LRUMap(50);
  private static Map<Path, Entry<String, Driver>> pendingRuns = new HashMap<Path, Map.Entry<String,Driver>>();

  private static List<RelativeSourceLocationPath> allInputFiles = new ArrayList<RelativeSourceLocationPath>();
  private static List<Path> pendingInputFiles = new ArrayList<Path>();

  private static List<Path> currentlyProcessing = new ArrayList<Path>();

  private static List<ProcessingListener> processingListener = new LinkedList<ProcessingListener>();
  
  
  private IProgressMonitor monitor;
  
  private Environment environment = new Environment();
  
  private Result driverResult;
  
//  private Path javaOutFile;
//  private JavaSourceFileContent javaSource;
  private Path depOutFile;

  private RelativeSourceLocationPath sourceFile;

  private Path currentGrammarSDF;
  private String currentGrammarModule;
  private Path currentTransSTR;
  private String currentTransModule;
  private String remainingInput;
  
  // private Collection<String> dependentFiles;

  private List<String> availableSDFImports;
  private List<String> availableSTRImports;
  
  private IStrategoTerm sugaredPackageDecl;
  private List<IStrategoTerm> sugaredImportDecls = new ArrayList<IStrategoTerm>();
  private List<IStrategoTerm> sugaredTypeOrSugarDecls = new ArrayList<IStrategoTerm>();
  
  private IStrategoTerm lastSugaredToplevelDecl;
  
  private RetractableTreeBuilder inputTreeBuilder;
  private SGLR sdfParser;
  private SGLR strParser;
  private SGLR editorServicesParser;
  private SGLR parser;
  private Context sdfContext;
  private Context makePermissiveContext;
  private Context extractionContext;
  private Context strjContext;
  
  private ModuleKeyCache<Path> sdfCache = null;
  private ModuleKeyCache<Path> strCache = null;
  
  private Path currentGrammarTBL;
  private Path currentTransProg;
  
  private boolean interrupt = false;
  
  private boolean generateFiles;
  private Path delegateCompilation = null;
  
  
  
  private LanguageLib langLib;
  
  
  public Driver(Environment env, LanguageLibFactory langLibFactory) {
    this.environment=env;
    this.langLib = langLibFactory.createLanguageLibrary();
    
    try {      
      if (environment.getCacheDir() != null)
        FileCommands.createDir(environment.getCacheDir());
      
      FileCommands.createDir(environment.getBin());
      
      initializeCaches(environment, false);
    } catch (IOException e) {
      throw new RuntimeException("error while initializing driver", e);
    }
    
    
  }  
  
  
  private static synchronized Result getResult(Path file) {
    return (Result) resultCache.get(file);
  }
  
  private static synchronized Entry<String, Driver> getPendingRun(Path file) {
    return pendingRuns.get(file);
  }
  
  private static synchronized void putPendingRun(Path file, String source, Driver driver) {
    pendingRuns.put(file, new AbstractMap.SimpleImmutableEntry<String, Driver>(source, driver));
  }
  
  public static synchronized void addProcessingDoneListener(ProcessingListener listener) {
    processingListener.add(listener);
  }
  
  public static synchronized void removeProcessingDoneListener(ProcessingListener listener) {
    processingListener.remove(listener);
  }
  
  private static void waitForPending(Path file) {
    int count = 0;
    Object lock = new Object();
    synchronized (lock) {
      while (true) {
        synchronized (pendingRuns) {
          if (!pendingRuns.containsKey(file))
            return;
        }
        
        if (count > PENDING_TIMEOUT)
          throw new IllegalStateException("pending result timed out for " + file);
        
        count += 100;
        try {
          lock.wait(100);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  private static synchronized void putResult(Path file, Result result) {
    resultCache.put(file, result);
    Log.log.log(resultCache.size());
  }
  
  public static Result compile(RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, LanguageLibFactory langLibFactory) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(sourceFile, monitor, true, langLibFactory);
  }

  public static Result parse(RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, LanguageLibFactory langLibFactory) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(sourceFile, monitor, false, langLibFactory);
  }
  
  public static Result compile(String source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, LanguageLibFactory langLibFactory) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, monitor, true, langLibFactory);
  }
  
  public static Result parse(String source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, LanguageLibFactory langLibFactory) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, monitor, false, langLibFactory);
  }

  private static Result run(RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, boolean generateFiles, LanguageLibFactory langLibFactory) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    if (generateFiles)
      synchronized (currentlyProcessing) {
        // TODO we need better circular dependency handling
        if (currentlyProcessing.contains(sourceFile))
          ;
          // throw new IllegalStateException("circular processing");
        currentlyProcessing.add(sourceFile);
      }

    Result res;
    
    try {
      String source = FileCommands.readFileAsString(sourceFile);
      res = run(source, sourceFile, monitor, generateFiles, langLibFactory);
    } finally {
      if (generateFiles)
        synchronized (currentlyProcessing) {
          currentlyProcessing.remove(sourceFile);
        }
      pendingInputFiles.remove(sourceFile);
    }

    return res;
  }
  
  private static Result run(String source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, boolean generateFiles, LanguageLibFactory langLibFactory) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Driver driver = new Driver(sourceFile.getSourceLocation().getEnvironment(), langLibFactory);
    Entry<String, Driver> pending = null;
    
    synchronized (Driver.class) {
      pending = getPendingRun(sourceFile);
      if (pending != null && !pending.getKey().equals(source)) {
        log.log("interrupting " + sourceFile);
        pending.getValue().interrupt();
      }

      if (pending == null) {
        Result result = getResult(sourceFile);
        if (result != null && result.isUpToDate(source.hashCode(), sourceFile.getSourceLocation().getEnvironment()))
          return result;
      }
      
      if (pending == null)
        putPendingRun(sourceFile, source, driver);
    }
    
    if (pending != null) {
      waitForPending(sourceFile);
      return run(source, sourceFile, monitor, generateFiles, langLibFactory);
    }
    
    try {
      synchronized (processingListener) {
        for (ProcessingListener listener : processingListener)
          listener.processingStarts(sourceFile);
      }
    
      driver.process(source, sourceFile, monitor, generateFiles);
      if (!Environment.rocache)
        driver.storeCaches(sourceFile.getSourceLocation().getEnvironment());
    
      synchronized (processingListener) {
        for (ProcessingListener listener : processingListener)
          listener.processingDone(driver.driverResult);
      }
    } catch (Exception e) {
      org.strategoxt.imp.runtime.Environment.logException(e);
    } finally {
      pendingRuns.remove(sourceFile);
      if (generateFiles)
        putResult(sourceFile, driver.driverResult != null && driver.driverResult.getSugaredSyntaxTree() == null ? null : driver.driverResult);
    }

    return driver.driverResult;
  }
  
  /**
   * Process the given Extensible Java file.
   * 
   * @throws IOException 
   * @throws SGLRException 
   * @throws InvalidParseTableException 
   * @throws ParseException 
   * @throws BadTokenException 
   * @throws TokenExpectedException 
   * @throws InterruptedException 
   */
  private void process(String source, RelativePath sourceFile, IProgressMonitor monitor, boolean generateFiles) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    this.monitor = monitor;
    log.beginTask("processing", "BEGIN PROCESSING " + sourceFile.getRelativePath());
    boolean success = false;
    try {
      init(sourceFile, generateFiles);
      driverResult.setSourceFile(this.sourceFile, source.hashCode());
      
      if (sourceFile != null) {
        langLib.setupSourceFile(sourceFile, environment);

        depOutFile = environment.createBinPath(FileCommands.dropExtension(sourceFile.getRelativePath()) + ".dep");
        Path genLog = environment.createBinPath(FileCommands.dropExtension(sourceFile.getRelativePath()) + ".gen");
        driverResult.setGenerationLog(genLog);
        clearGeneratedStuff();
      }

      remainingInput = source;
  
      initEditorServices();

      boolean done = false;
      while (!done) {
        stepped();
        
        // PARSE the next top-level declaration
        IncrementalParseResult parseResult =
            parseNextToplevelDeclaration(remainingInput, true);
        lastSugaredToplevelDecl = parseResult.getToplevelDecl();
        remainingInput = parseResult.getRest();
        
        stepped();
        
        // DESUGAR the parsed top-level declaration
        IStrategoTerm desugared = currentDesugar(lastSugaredToplevelDecl);
        
        stepped();
        
        // PROCESS the assimilated top-level declaration
        processToplevelDeclaration(desugared);

        done = parseResult.parsingFinished();
      }
      
      stepped();
            
      // check final grammar and transformation for errors
      if (!environment.isNoChecking()) {
        checkCurrentGrammar();
      }
      
      stepped();
      
      // need to build current transformation program for editor services
      checkCurrentTransformation();
      
      stepped();
      
      // COMPILE the generated java file
      if (delegateCompilation == null)
        compileGeneratedJavaFiles();
      else {
        driverResult.delegateCompilation(delegateCompilation, langLib.getOutFile(), langLib.getSource(), langLib.getGeneratedFiles());
      }
        
      driverResult.setSugaredSyntaxTree(makeSugaredSyntaxTree());
      
      if (currentGrammarTBL != null)
        driverResult.registerParseTable(currentGrammarTBL);
      
      if (currentTransProg != null) {
        driverResult.addEditorService(
            ATermCommands.atermFromString(
              "Builders(\"sugarj checking\", [SemanticObserver(Strategy(\"sugarj-analyze\"))])"));
        driverResult.registerEditorDesugarings(currentTransProg);
      }

      driverResult.writeDependencyFile(depOutFile);

      success = true;
    } 
    catch (CommandExecution.ExecutionError e) {
      // TODO do something more sensible
      e.printStackTrace();
      success = false;
    }
    finally {
      log.endTask(success, "done processing " + sourceFile, "failed processing " + sourceFile);
      driverResult.setFailed(!success);
    }
  }

  private void compileGeneratedJavaFiles() throws IOException {
    boolean good = false;
    log.beginTask("compilation", "COMPILE the generated " + langLib.getLanguageName() + " files");
    try {
      try {
        langLib.compile(langLib.getOutFile(), langLib.getSource(),
            environment.getBin(), new ArrayList<Path>(environment.getIncludePath()), langLib.getGeneratedFiles(),
            driverResult.getAvailableGeneratedFiles().get(driverResult.getSourceFile()),
            driverResult.getDeferredSourceFiles().get(driverResult.getSourceFile()),
            driverResult.getGeneratedFileHashes(), 
            driverResult.isGenerateFiles());
      } catch (ClassNotFoundException e) {
        setErrorMessage(lastSugaredToplevelDecl, "Could not resolve imported class " + e.getMessage());
        // throw new RuntimeException(e);
      }
      good = true;
    } finally {
      log.endTask(good, "compilation succeeded", "compilation failed");
    }
  }

  private IncrementalParseResult parseNextToplevelDeclaration(String input, boolean recovery)
      throws IOException, ParseException, InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    int start = inputTreeBuilder.getTokenizer() == null ? 0 : inputTreeBuilder.getTokenizer().getStartOffset();
    log.beginTask("parsing", "PARSE the next toplevel declaration.");
    try {
      IStrategoTerm remainingInputTerm = null;
      
      try {
        remainingInputTerm = currentParse(input, recovery);
      } catch (Exception e) {
        if (recovery) {
          e.printStackTrace();
          remainingInputTerm = currentParse(input, false);
        }
        
        if (remainingInputTerm == null)
          throw e;
      }

      if (remainingInputTerm == null)
        throw new ParseException("could not parse toplevel declaration in:\n"
            + input, -1);

//      remainingInputTerm = ATermCommands.pushAmbiguities(remainingInputTerm);
      
      if (!isApplication(remainingInputTerm, "NextToplevelDeclaration"))
        throw new ATermCommands.MatchError(remainingInputTerm, "NextToplevelDeclaration");
      
      IStrategoTerm toplevelDecl = getApplicationSubterm(remainingInputTerm, "NextToplevelDeclaration", 0);
      IStrategoTerm restTerm = getApplicationSubterm(remainingInputTerm, "NextToplevelDeclaration", 1);
      if (isApplication(restTerm, "amb"))
        restTerm = restTerm.getSubterm(0).getSubterm(0);
      String rest = getString(restTerm);

      if (input.equals(rest))
        throw new SGLRException(parser, "empty toplevel declaration parse rule");
      
      try {
        if (!rest.isEmpty())
          inputTreeBuilder.retract(restTerm);
      } catch (Throwable t) {
        t.printStackTrace();
      }
      
      if (toplevelDecl == null || rest == null)
        throw new ParseException(
            "could not parse next toplevel declaration in:\n"
                + remainingInputTerm.toString(),
            -1);
      
      Path tmpFile = FileCommands.newTempFile("aterm");
      FileCommands.writeToFile(tmpFile, toplevelDecl.toString());
      log.log("next toplevel declaration parsed: " + tmpFile);

      return new IncrementalParseResult(toplevelDecl, rest);
    } catch (Exception e) {
      if (!recovery)
        throw new SGLRException(parser, "parsing failed", e);
      
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      
      if (!(e instanceof StrategoException) && !(e instanceof SGLRException))
        e.printStackTrace();
      else
        log.logErr(msg);
      
      if (inputTreeBuilder.getTokenizer().getStartOffset() > start) {
        IToken token = inputTreeBuilder.getTokenizer().getTokenAtOffset(start);
        ((RetractableTokenizer) inputTreeBuilder.getTokenizer()).retractTo(token.getIndex());
        inputTreeBuilder.setOffset(start);
      }
      
      IToken right = inputTreeBuilder.getTokenizer().makeToken(start + input.length() - 1, IToken.TK_STRING, true);
      IToken left = inputTreeBuilder.getTokenizer().getTokenAtOffset(start);
      inputTreeBuilder.getTokenizer().makeToken(inputTreeBuilder.getTokenizer().getStartOffset() - 1, IToken.TK_EOF, true);
      IStrategoTerm term = ATermCommands.factory.makeString(input);
      ImploderAttachment.putImploderAttachment(term, false, "String", left, right);
      setErrorMessage(term, msg);
      return new IncrementalParseResult(term, "");
    } finally {
      log.endTask();
    }
  }

  
  private void processEditorServicesDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask(
        "processing",
        "PROCESS the desugared editor services declaration.");
    try {
      if (!sugaredTypeOrSugarDecls.contains(lastSugaredToplevelDecl))
        sugaredTypeOrSugarDecls.add(lastSugaredToplevelDecl);
      
      String extName = null;
      String fullExtName = null;
      boolean isPublic = false;

      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "EditorServicesDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "EditorServicesDec", 1);
      
      log.beginTask("Extracting name and accessibility of the editor services.");
      try {
        extName =
          langLib.prettyPrint(
          getApplicationSubterm(head, "EditorServicesDecHead", 1));    
        
        
        
        IStrategoTerm mods = getApplicationSubterm(head, "EditorServicesDecHead", 0);
        
        for (IStrategoTerm t : getList(mods))
          if (isApplication(t, "Public"))
          {
            isPublic = true;
            break;
          }
        
        fullExtName = langLib.getRelativeNamespaceSep() + extName;

        log.log("The name of the editor services is '" + extName + "'.");
        log.log("The full name of the editor services is '" + fullExtName + "'.");

        if (isPublic)
          log.log("The editor services is public.");
        else
          log.log("The editor services is not public.");
      
        IStrategoTerm services = ATermCommands.getApplicationSubterm(body, "EditorServicesBody", 0);
        
        if (!ATermCommands.isList(services))
          throw new IllegalStateException("editor services are not a list: " + services);
        
        List<IStrategoTerm> editorServices = ATermCommands.getList(services);
        
        // XXX if (currentTransProg != null)
        editorServices = ATermCommands.registerSemanticProvider(editorServices, currentTransProg);
  
        Path editorServicesFile = environment.createBinPath(langLib.getRelativeNamespaceSep() + extName + ".serv");
        
        log.log("writing editor services to " + editorServicesFile);
        
        StringBuffer buf = new StringBuffer();
        
        for (IStrategoTerm service : driverResult.getEditorServices())
          buf.append(service).append('\n');
        
        for (IStrategoTerm service : editorServices) {
          driverResult.addEditorService(service);
          buf.append(service).append('\n');
        }
        
        driverResult.generateFile(editorServicesFile, buf.toString());
      } finally {
        log.endTask();
      }
    } finally {
      log.endTask();
    }
  }
  
  private void processPlainDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask(
        "processing",
        "PROCESS the desugared plain declaration.");
    try {
      if (!sugaredTypeOrSugarDecls.contains(lastSugaredToplevelDecl))
        sugaredTypeOrSugarDecls.add(lastSugaredToplevelDecl);

      
      String extName = null;
      String fullExtName = null;
      boolean isPublic = false;

      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "PlainDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "PlainDec", 1);
      
      log.beginTask("Extracting name and accessibility.");
      try {
        extName =
          langLib.prettyPrint(
          getApplicationSubterm(head, "PlainDecHead", 1));    

        String extension = null;
        if (head.getSubtermCount() >= 3 && isApplication(getApplicationSubterm(head, "PlainDecHead", 2), "Some"))
          extension = Term.asJavaString(
                      getApplicationSubterm(getApplicationSubterm(head, "PlainDecHead", 2), "Some", 0));    
 

        
        IStrategoTerm mods = getApplicationSubterm(head, "PlainDecHead", 0);
        
        for (IStrategoTerm t : getList(mods))
          if (isApplication(t, "Public"))
          {
            isPublic = true;
            break;
          }
        
        fullExtName = langLib.getRelativeNamespaceSep() + extName + (extension == null ? "" : ("." + extension));

        log.log("The name is '" + extName + "'.");
        log.log("The full name is '" + fullExtName + "'.");

        if (isPublic)
          log.log("The plain file is public.");
        else
          log.log("The plain file is not public.");
      
        String plainContent = Term.asJavaString(ATermCommands.getApplicationSubterm(body, "PlainBody", 0));
        
        String ext = extension == null ? "" : ("." + extension);
        Path plainFile = environment.createBinPath(langLib.getRelativeNamespaceSep() + extName + ext);
        FileCommands.createFile(plainFile);
  
        log.log("writing plain content to " + plainFile);
        driverResult.generateFile(plainFile, plainContent);
      } finally {
        log.endTask();
      }
    } finally {
      log.endTask();
    }
  }
  
  
  private void processToplevelDeclaration(IStrategoTerm toplevelDecl)
      throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException {
    if (langLib.isNamespaceDec(toplevelDecl))
      processPackageDec(toplevelDecl);
    else {
//      langLib.checkNamespace(toplevelDecl, sourceFile, driverResult);   
//      langLib.checkSourceOutFile(environment, driverResult.getSourceFile());
      if (depOutFile == null) {
        // does this ever occur?
        assert false;
        depOutFile = environment.createBinPath(langLib.getRelativeNamespaceSep() + FileCommands.fileName(driverResult.getSourceFile()) + ".dep");
      }
      try {
        if (langLib.isImportDec(toplevelDecl)) {
          if (!environment.isAtomicImportParsing())
            processImportDec(toplevelDecl);
          else 
            processImportDecs(toplevelDecl);
        }
        else if (langLib.isLanguageSpecificDec(toplevelDecl))
          processJavaTypeDec(toplevelDecl);
        else if (langLib.isSugarDec(toplevelDecl))
          processSugarDec(toplevelDecl);
        else if (langLib.isEditorServiceDec(toplevelDecl)) 
          processEditorServicesDec(toplevelDecl);
        else if (langLib.isPlainDec(toplevelDecl))   // XXX: Decide what to do with "Plain"--leave in the language or create a new "Plain" language
          processPlainDec(toplevelDecl);
        else if (ATermCommands.isList(toplevelDecl))
          /* 
           * Desugarings may generate lists of toplevel declarations. These declarations,
           * however, may not depend on one another.
           */
          for (IStrategoTerm term : ATermCommands.getList(toplevelDecl))
            processToplevelDeclaration(term);
        else if (ATermCommands.isString(toplevelDecl)) {
          if (!sugaredTypeOrSugarDecls.contains(lastSugaredToplevelDecl))
            sugaredTypeOrSugarDecls.add(lastSugaredToplevelDecl);
        }
        else
          throw new IllegalArgumentException("unexpected toplevel declaration, desugaring probably failed: " + toplevelDecl.toString(20));
      } catch (Exception e) {
        String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
        
        if (!(e instanceof StrategoException))
          e.printStackTrace();
        else
          log.logErr(msg);

        setErrorMessage(toplevelDecl, msg);
        if (!sugaredTypeOrSugarDecls.contains(lastSugaredToplevelDecl))
          sugaredTypeOrSugarDecls.add(lastSugaredToplevelDecl);

      }
    }
  }

  private IStrategoTerm currentParse(String remainingInput, boolean recovery) throws IOException,
      InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    // recompile the current grammar definition
    currentGrammarTBL = SDFCommands.compile(currentGrammarSDF, currentGrammarModule, driverResult.getFileDependencies(environment), sdfParser, sdfContext, makePermissiveContext, sdfCache, environment, langLib);
//    FileCommands.deleteTempFiles(driverResult.getLastParseTable());
//    driverResult.setLastParseTable(currentGrammarTBL);
    ParseTable table = ATermCommands.parseTableManager.loadFromFile(currentGrammarTBL.getAbsolutePath());
    
    Pair<SGLR, IStrategoTerm> parseResult = null;

    // read next toplevel decl and stop if that fails
    try {
      parseResult = SDFCommands.parseImplode(
          table,
          remainingInput,
          "NextToplevelDeclaration",
          recovery,
          inputTreeBuilder);
    } catch (SGLRException e) {
      this.parser = e.getParser();
      log.logErr(e.getMessage());
      return null;
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

  private IStrategoTerm currentDesugar(IStrategoTerm term) throws IOException,
      InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    // assimilate toplevelDec using current transformation

    log.beginTask(
        "desugaring",
        "DESUGAR the current toplevel declaration.");
    try {
      FileCommands.deleteTempFiles(currentTransProg);
      currentTransProg = STRCommands.compile(currentTransSTR, "main", driverResult.getFileDependencies(environment), strParser, strjContext, strCache, environment, langLib);

      return STRCommands.assimilate(currentTransProg, term, langLib.getInterpreter());
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      
      log.logErr(msg);

      setErrorMessage(term, msg);
      return term;
    } finally {
      log.endTask();
    }
  }

  private void processPackageDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS the desugared package declaration.");
    try {
      sugaredPackageDecl = lastSugaredToplevelDecl;

      langLib.processNamespaceDec(toplevelDecl, environment, driverResult, sourceFile, driverResult.getSourceFile());    
      if (depOutFile == null)
        depOutFile = environment.createBinPath(langLib.getRelativeNamespaceSep() + FileCommands.fileName(driverResult.getSourceFile()) + ".dep");
      
    } finally {
      log.endTask();
    }
  }
  
//  private void checkPackageName(IStrategoTerm toplevelDecl) {
//    if (sourceFile != null) {
//      String packageName = relPackageName == null ? "" : relPackageName.replace('/', '.');
//      
//      String rel = FileCommands.dropExtension(sourceFile.getRelativePath());
//      int i = rel.lastIndexOf('/');
//      String expectedPackage = i >= 0 ? rel.substring(0, i) : rel;
//      expectedPackage = expectedPackage.replace('/', '.');
//      if (!packageName.equals(expectedPackage))
//        setErrorMessage(
//            toplevelDecl,
//            "The declared package '" + packageName + "'" +
//            " does not match the expected package '" + expectedPackage + "'.");
//    }
//  }
  
  private void processImportDecs(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException {
    List<IStrategoTerm> pendingImports = new ArrayList<IStrategoTerm>();
    pendingImports.add(toplevelDecl);
    
    while (true) {
      IncrementalParseResult res = null;
      IStrategoTerm term = null;
      
      try {
        log.beginSilent();
        res = parseNextToplevelDeclaration(remainingInput, false);
        term = res.getToplevelDecl();
      }
      catch (Throwable t) {
        res = null;
        term = null;
      }
      finally {         
        log.endSilent(); 
      }
    
      if (res != null &&
          term != null &&
          langLib.isImportDec(term)) {
        remainingInput = res.getRest();
        pendingImports.add(term);
      }
      else {
        if (term != null)
          inputTreeBuilder.retract(term);
        break;
      }
    }
    
    for (IStrategoTerm pendingImport : pendingImports) {
      lastSugaredToplevelDecl = pendingImport;
      processImportDec(pendingImport);
    }
  }

  private void processImportDec(IStrategoTerm toplevelDecl) {
    
    sugaredImportDecls.add(lastSugaredToplevelDecl);
    
    log.beginTask("processing", "PROCESS the desugared import declaration.");
    try {

      // TODO handle import declarations with asterisks, e.g. import foo.*;
            
      String modulePath = langLib.getImportedModulePath(toplevelDecl);
      String importModuleName = FileCommands.fileName(modulePath);
      
      boolean skipProcessImport = false;
      
      if (!modulePath.startsWith("org/sugarj")) { // module is not in sugarj standard library
        Path dep = ModuleSystemCommands.searchFile(modulePath, ".dep", environment);
        Result res = null;
        RelativeSourceLocationPath importSourceFile = null;
        
        if (dep != null) {
          try {
            res = Result.readDependencyFile(dep, environment);
          } catch (IOException e) {
            log.logErr("could not read dependency file " + dep);
          }
          
          if (res != null && res.getSourceFile() != null)
            importSourceFile = res.getSourceFile();
        }
        
        if (importSourceFile == null)
          importSourceFile = ModuleSystemCommands.locateSourceFile(modulePath, environment.getSourcePath(), langLib);

        if (importSourceFile != null && (res == null || pendingInputFiles.contains(res.getSourceFile()) || !res.isUpToDate(res.getSourceFile(), environment))) {
          if (!generateFiles) {
            // boolean b = res == null || pendingInputFiles.contains(res.getSourceFile()) || !res.isUpToDate(res.getSourceFile(), environment);
            // System.out.println(b);
            setErrorMessage(toplevelDecl, "module outdated, compile first: " + importModuleName);
          }
          else {
            log.log("Need to compile the imported module first ; processing it now.");
            
            try {
              storeCaches(environment);
   
              if (currentlyProcessing.contains(importSourceFile)) {
                // assume source file does not provide syntactic sugar
                //langLib.getSource().addImport(importModuleName.replace('/', '.'));
                langLib.addImportModule(toplevelDecl, false);
                skipProcessImport = true;
                delegateCompilation = importSourceFile;
              }
              else {
                res = compile(importSourceFile, monitor, langLib.getFactoryForLanguage());    // XXX: Think of a better way to handle this
                initializeCaches(environment, true);
                if (res.hasFailed())
                  setErrorMessage(toplevelDecl, "problems while compiling " + importModuleName);
              }
            } catch (Exception e) {
              setErrorMessage(toplevelDecl, "problems while compiling " + importModuleName);
            }
              
            log.log("CONTINUE PROCESSING'" + importSourceFile + "'.");
          }
        }
        
        if (dep == null)
          dep = ModuleSystemCommands.searchFile(modulePath, ".dep", environment);
        
        if (res == null && dep != null)
          res = Result.readDependencyFile(dep, environment);
        
        if (res != null && !skipProcessImport)
          driverResult.addDependency(res, environment);
        
        if (res != null && res.hasDelegatedCompilation(sourceFile)) {
          delegateCompilation = res.getSourceFile();
          langLib.addImportModule(toplevelDecl, false);
          skipProcessImport = true;
        }
        else if (driverResult.hasDelegatedCompilation(importSourceFile)) {
          langLib.addImportModule(toplevelDecl, false);
          skipProcessImport = true;
        }
      }

      boolean success = skipProcessImport || processImport(modulePath, toplevelDecl);
      
      if (!success)
        setErrorMessage(toplevelDecl, "module not found: " + importModuleName);
      
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      log.endTask();
    }
  }
  
  private boolean processImport(String modulePath, IStrategoTerm importTerm) throws IOException {
    boolean success = false;
    
    //success |= ModuleSystemCommands.importClass(modulePath, langLib.getSource(), environment, langLib);
    success |= ModuleSystemCommands.importClass(modulePath, importTerm, environment, langLib);
    ModuleSystemCommands.registerSearchedClassFiles(modulePath, driverResult, environment, langLib);

    Path sdf = ModuleSystemCommands.importSdf(modulePath, environment);
    ModuleSystemCommands.registerSearchedSdfFiles(modulePath, driverResult, environment);
    if (sdf != null) {
      success = true;
      availableSDFImports.add(modulePath);
      buildCompoundSdfModule();
    }
    
    Path str = ModuleSystemCommands.importStratego(modulePath, environment);
    ModuleSystemCommands.registerSearchedStrategoFiles(modulePath, driverResult, environment);
    if (str != null) {
      success = true;
      availableSTRImports.add(modulePath);
      buildCompoundStrModule();
    }
    
    success |= ModuleSystemCommands.importEditorServices(modulePath, driverResult, environment);
    ModuleSystemCommands.registerSearchedEditorServicesFiles(modulePath, driverResult, environment);
    
    return success;
  }

  private void processJavaTypeDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask(
        "processing",
        "PROCESS the desugared " + langLib.getLanguageName() + " type declaration.");
    try {
      
      if (!sugaredTypeOrSugarDecls.contains(lastSugaredToplevelDecl))
        sugaredTypeOrSugarDecls.add(lastSugaredToplevelDecl);

      
      log.beginTask("Generate " + langLib.getLanguageName() + " code.");
      try {
        langLib.processLanguageSpecific(toplevelDecl, environment);
      } finally {
        log.endTask();
      }
    } finally {
      log.endTask();
    }
  }

  private void processSugarDec(IStrategoTerm toplevelDecl) throws IOException,
      InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    log.beginTask(
        "processing",
        "PROCESS the desugared sugar declaration.");
    try {
      if (!sugaredTypeOrSugarDecls.contains(lastSugaredToplevelDecl))
        sugaredTypeOrSugarDecls.add(lastSugaredToplevelDecl);

      
      String extName = null;
      String fullExtName = null;
      boolean isPublic = false;
      
      log.beginTask("Extracting name and accessibility of the sugar.");
      try {        
        extName = langLib.getSugarName(toplevelDecl);

        if (langLib.getSugarAccessibility(toplevelDecl) == LanguageLib.PUBLIC_SUGAR) {
          isPublic = true;
        }


        fullExtName = langLib.getRelativeNamespaceSep() + extName;

        log.log("The name of the sugar is '" + extName + "'.");
        log.log("The full name of the sugar is '" + fullExtName + "'.");

        if (isPublic)
          log.log("The sugar is public.");
        else
          log.log("The sugar is not public.");
        
      } finally {
        log.endTask();
      }
      
      Path sdfExtension = environment.createBinPath(langLib.getRelativeNamespaceSep() + extName + ".sdf");
      Path strExtension = environment.createBinPath(langLib.getRelativeNamespaceSep() + extName + ".str");
      
      String sdfImports = " imports " + StringCommands.printListSeparated(availableSDFImports, " ") + "\n";
      String strImports = " imports " + StringCommands.printListSeparated(availableSTRImports, " ") + "\n";
      
        // this is a list of SDF and Stratego statements
        
        IStrategoTerm sugarBody = langLib.getSugarBody(toplevelDecl);
  
        IStrategoTerm sdfExtract = fixSDF(extractSDF(sugarBody, extractionContext), langLib.getInterpreter());
        IStrategoTerm strExtract = extractSTR(sugarBody, extractionContext);
        
        
        String sdfExtensionHead =
          "module " + fullExtName + "\n" 
          + sdfImports
          + (isPublic ? "exports " : "hiddens ") + "\n"
          + "  (/)" + "\n";

        String sdfExtensionContent = SDFCommands.prettyPrintSDF(sdfExtract, langLib.getInterpreter());

        String sdfSource = SDFCommands.makePermissiveSdf(sdfExtensionHead + sdfExtensionContent, makePermissiveContext);
        driverResult.generateFile(sdfExtension, sdfSource);
        availableSDFImports.add(fullExtName);
        
        if (CommandExecution.FULL_COMMAND_LINE && generateFiles)
          log.log("Wrote SDF file to '" + sdfExtension.getAbsolutePath() + "'.");
        
        String strExtensionTerm = 
            "Module(" + "\"" + fullExtName+ "\"" + ", " 
                      + strExtract + ")" + "\n";

        String strExtensionContent = SDFCommands.prettyPrintSTR(ATermCommands.atermFromString(strExtensionTerm), langLib.getInterpreter());
        
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
        
        if (CommandExecution.FULL_COMMAND_LINE && generateFiles)
          log.log("Wrote Stratego file to '" + strExtension.getAbsolutePath() + "'.");
      
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

  private void checkCurrentGrammar() throws IOException, InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    log.beginTask("checking grammar", "CHECK current grammar");
    
    try {
      Path p = SDFCommands.compile(currentGrammarSDF, currentGrammarModule, driverResult.getFileDependencies(environment), sdfParser, sdfContext, makePermissiveContext, sdfCache, environment, langLib);
      FileCommands.deleteTempFiles(p);
    } finally {
      log.endTask();
    }
  }
  
  private void checkCurrentTransformation() throws TokenExpectedException, BadTokenException, IOException, InvalidParseTableException, SGLRException {
    log.beginTask("checking transformation", "CHECK current transformation");
    
    try {
      FileCommands.deleteTempFiles(currentTransProg);
      currentTransProg = STRCommands.compile(currentTransSTR, "main", driverResult.getFileDependencies(environment), strParser, strjContext, strCache, environment, langLib);
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      log.logErr(msg);
      setErrorMessage(lastSugaredToplevelDecl, msg);
    } finally {
      log.endTask();
    }
  }
  
  private void initEditorServices() throws IOException, TokenExpectedException, BadTokenException, SGLRException, InterruptedException {
    IStrategoTerm initEditor = (IStrategoTerm) editorServicesParser.parse(FileCommands.readFileAsString(langLib.getInitEditor()), langLib.getInitEditor().getPath(), "Module");

    IStrategoTerm services = ATermCommands.getApplicationSubterm(initEditor, "Module", 2);
    
    if (!ATermCommands.isList(services))
      throw new IllegalStateException("initial editor ill-formed");
    
    for (IStrategoTerm service : ATermCommands.getList(services))
      driverResult.addEditorService(service);
  }
  
  private void init(RelativePath sourceFile, boolean generateFiles) throws FileNotFoundException, IOException, InvalidParseTableException {
    environment.getIncludePath().add(new AbsolutePath(langLib.getLibraryDirectory().getAbsolutePath()));
    //     includePath.add(new AbsolutePath(StdLib.stdLibDir.getAbsolutePath()));

    depOutFile = null;
    // FileCommands.createFile(tmpOutdir, relModulePath + ".java");

    this.sourceFile = new RelativeSourceLocationPath(new SourceLocation(sourceFile.getBasePath(), environment), sourceFile);
    this.generateFiles = generateFiles;
    
    this.driverResult = new Result(generateFiles);

    currentGrammarSDF = new AbsolutePath(langLib.getInitGrammar().getPath());
    currentGrammarModule = langLib.getInitGrammarModule();
    
    currentTransSTR = new AbsolutePath(langLib.getInitTrans().getPath());
    currentTransModule = langLib.getInitTransModule();
    
    // list of imports that contain SDF extensions
    availableSDFImports = new ArrayList<String>();    
    availableSDFImports.add(langLib.getInitGrammarModule());

    // list of imports that contain Stratego extensions
    availableSTRImports = new ArrayList<String>();
    availableSTRImports.add(langLib.getInitTransModule());

    inputTreeBuilder = new RetractableTreeBuilder();
    
    sdfParser = new SGLR(new TreeBuilder(), ATermCommands.parseTableManager.loadFromFile(StdLib.sdfTbl.getPath()));
    strParser = new SGLR(new TreeBuilder(), ATermCommands.parseTableManager.loadFromFile(StdLib.strategoTbl.getPath()));
    editorServicesParser = new SGLR(new TreeBuilder(), ATermCommands.parseTableManager.loadFromFile(StdLib.editorServicesTbl.getPath()));

    //interp = new HybridInterpreter(); //TODO (ATermCommands.factory);
    langLib.setInterpreter(new HybridInterpreter());
    sdfContext = tools.init();
    makePermissiveContext = make_permissive.init();
    extractionContext = extraction.init();
    strjContext = org.strategoxt.strj.strj.init();
  }
  
  /**
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) {
        LanguageLib langLib = UsedLanguageLibrary.getFreshLanguageLibrary();
    
    // log.log("This is the extensible java compiler.");
    Environment environment = new Environment();
    
    try {
      String[] sources = DriverCLI.handleOptions(args, environment);

      if (environment.getSourcePath().isEmpty())
        environment.getSourcePath().add(new SourceLocation(new AbsolutePath("."), environment));
      
      for (String source : sources)
      {
        RelativeSourceLocationPath p = ModuleSystemCommands.locateSourceFile(FileCommands.dropExtension(source), environment.getSourcePath(), langLib);
        
        allInputFiles.add(p);
        pendingInputFiles.add(p);
      }
      
      IProgressMonitor monitor = new PrintProgressMonitor(System.out);
      
      for (final RelativeSourceLocationPath sourceFile : allInputFiles) {
        monitor.beginTask("compile " + sourceFile, IProgressMonitor.UNKNOWN);
        Result res = compile(sourceFile, monitor, langLib.getFactoryForLanguage());
        if (!DriverCLI.processResultCLI(res, sourceFile, new File(".").getAbsolutePath()))
          throw new RuntimeException("compilation of " + sourceFile + " failed");
      }
      
    } catch (Exception e) {
      e.printStackTrace();
    } catch (CLIError e) {
      Log.log.log(e.getMessage());
      Log.log.log("");
      e.showUsage();
    }

    // kills all remaining subprocesses, if any
    // log.log("The extensible java compiler has done its job and says 'good bye'.");
    System.exit(0);
  }
  
  
  @SuppressWarnings("unchecked")
  private void initializeCaches(Environment environment, boolean force) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path cacheVersion = environment.createCachePath("version");
    
    if (!cacheVersion.getFile().exists() ||
        !FileCommands.readFileAsString(cacheVersion).equals(CACHE_VERSION)) {

      for (File f : environment.getCacheDir().getFile().listFiles())
        f.delete();
      
      FileCommands.writeToFile(cacheVersion, CACHE_VERSION);
    }
    
    Path sdfCachePath = environment.createCachePath("sdfCache");
    Path strCachePath = environment.createCachePath("strCache");
    
    if (sdfCache == null || force)
      try {
        // log.log("load sdf cache from " + sdfCachePath);
          sdfCache = reallocate(
              (ModuleKeyCache<Path>) new ObjectInputStream(new FileInputStream(sdfCachePath.getFile())).readObject(),
              environment);
      } 
      catch (Exception e) {
        sdfCache = new ModuleKeyCache<Path>();
        for (File f : environment.getCacheDir().getFile().listFiles())
          if (f.getPath().endsWith(".tbl"))
            f.delete();
      }
    else if (sdfCache == null)
      sdfCache = new ModuleKeyCache<Path>();
    
    if (strCache == null || force)
      try {
        // log.log("load str cache from " + strCachePath);
        strCache = reallocate(
            (ModuleKeyCache<Path>) new ObjectInputStream(new FileInputStream(strCachePath.getFile())).readObject(),
            environment);
      } 
      catch (Exception e) {
        strCache = new ModuleKeyCache<Path>();
        for (File f : environment.getCacheDir().getFile().listFiles())
          if (f.getPath().endsWith(".jar"))
            f.delete();
      }
    else if (strCache == null)
      strCache = new ModuleKeyCache<Path>();
  }

  
  private static ModuleKeyCache<Path> reallocate(ModuleKeyCache<Path> cache, Environment env) {
    ModuleKeyCache<Path> res = new ModuleKeyCache<Path>();
    
    for (Entry<ModuleKey, Path> e : cache.entrySet()) {
      Map<Path, Integer> imports = new HashMap<Path, Integer>();
      for (Entry<Path, Integer> e2 : e.getKey().imports.entrySet())
        imports.put(Path.reallocate(e2.getKey(), env), e2.getValue());
      
      res.put(new ModuleKey(imports, e.getKey().body), Path.reallocate(e.getValue(), env));
    }
    
    return res;
  }


  private void storeCaches(Environment environment) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path cacheVersion = environment.createCachePath("version");
    FileCommands.writeToFile(cacheVersion, CACHE_VERSION);
    
    Path sdfCachePath = environment.createCachePath("sdfCache");
    Path strCachePath = environment.createCachePath("strCache");

    if (!sdfCachePath.getFile().exists())
      FileCommands.createFile(sdfCachePath);

    if (!strCachePath.getFile().exists())
      FileCommands.createFile(strCachePath);
    
    if (sdfCache != null) {
//      log.log("store sdf cache in " + sdfCachePath);
//      log.log("sdf cache size: " + sdfCache.size());
      FileCommands.createFile(sdfCachePath);
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(sdfCachePath.getFile()));
      try {
        oos.writeObject(sdfCache);
      } finally {
        oos.close();
      }
    }
    
    if (strCache != null) {
//      log.log("store str cache in " + strCachePath);
//      log.log("str cache size: " + strCache.size());
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(strCachePath.getFile()));
      try {
        oos.writeObject(strCache);
      } finally {
        oos.close();
      }
    }
  }


  
  /**
   * @return the non-desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeSugaredSyntaxTree() {
    
    // XXX empty lists => no tokens
    IStrategoTerm packageDecl = ATermCommands.makeSome(sugaredPackageDecl, inputTreeBuilder.getTokenizer().getTokenAt(0));
    IStrategoTerm imports = 
      ATermCommands.makeList("JavaImportDec*", ImploderAttachment.getRightToken(packageDecl), sugaredImportDecls);
    IStrategoTerm body =
      ATermCommands.makeList("TypeOrSugarDec*", ImploderAttachment.getRightToken(imports), sugaredTypeOrSugarDecls);
    
    IStrategoTerm term =
      ATermCommands.makeAppl("CompilationUnit", "SugarCompilationUnit", 3,
        packageDecl,
        imports,
        body);
    
    ImploderAttachment.getTokenizer(term).setAst(term);
    ImploderAttachment.getTokenizer(term).initAstNodeBinding();
    
    return term;
  }
  
  public synchronized void interrupt() {
    this.interrupt = true;
  }
  
  private synchronized void stopIfInterrupted() throws InterruptedException {
    if (interrupt || monitor.isCanceled()) {
      monitor.setCanceled(true);
      log.log("interrupted " + sourceFile);
      throw new InterruptedException();
    }
  }

  private void stepped() throws InterruptedException {
    stopIfInterrupted();
    monitor.worked(1);
  }
  
  private void clearGeneratedStuff() throws IOException {
    if (generateFiles && driverResult.getGenerationLog() != null && FileCommands.exists(driverResult.getGenerationLog())) {

      ObjectInputStream ois = null;
      
      try {
        ois = new ObjectInputStream(new FileInputStream(driverResult.getGenerationLog().getFile()));
        while (true) {
          try {
            Path p = Path.readPath(ois, environment);
            FileCommands.delete(p);
          } catch (ClassNotFoundException e) { 
          }
        }
      } catch (EOFException e) {
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        if (ois != null)
          ois.close();
        FileCommands.delete(driverResult.getGenerationLog());
      }
    }
  }
  
  private void setErrorMessage(IStrategoTerm toplevelDecl, String msg) {
    driverResult.logError(msg);
    ATermCommands.setErrorMessage(toplevelDecl, msg);
  }
}
