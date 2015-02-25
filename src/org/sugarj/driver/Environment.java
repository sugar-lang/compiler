package org.sugarj.driver;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.sugarj.baselang.IORelay;
import org.sugarj.cleardep.stamp.Stamper;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;


/**
 * Shared execution environment.
 * 
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Environment implements IORelay, Serializable {
  
  private static final long serialVersionUID = -8403625415393122607L;

  private boolean terminateJVMAfterProcessing = true;
  
  private Path cacheDir = null;
  private Path targetDir = null;

  private Path root = new AbsolutePath(".");
  
  private Stamper stamper; 
  
  /* 
   * parse all imports simultaneously, i.e., not one after the other
   */
  private boolean atomicImportParsing = false;
  
  /*
   * don't check resulting sdf and stratego files after splitting
   */
  private boolean noChecking = false;

  private Path tmpDir = new AbsolutePath(System.getProperty("java.io.tmpdir"));
  
  private List<Path> sourcePath = new LinkedList<Path>();
  private List<Path> includePath = new LinkedList<Path>();
  
  public Environment(Path stdlibDirPath, Stamper stamper) {
    this.stamper = stamper;
    includePath.add(stdlibDirPath);
  }
  
  public Path getRoot() {
    return root;
  }

  public void setRoot(Path root) {
    this.root = root;
  }

  public void addToSourcePath(Path p) {
    sourcePath.add(p);
  }
  
  public List<Path> getSourcePath() {
    return Collections.unmodifiableList(new ArrayList<>(sourcePath));
  }

  public void setSourcePath(List<Path> sourcePath) {
    this.sourcePath = sourcePath;
  }

  public Path getBin() {
    return targetDir;
  }
  
  public void setBin(Path newTargetDir) {
    addToIncludePath(newTargetDir);
    this.targetDir = newTargetDir;
  }
  
  public Path getCacheDir() {
    return cacheDir;
  }

  public void setCacheDir(Path cacheDir) {
    this.cacheDir = cacheDir;
  }

  public boolean isAtomicImportParsing() {
    return atomicImportParsing;
  }

  public void setAtomicImportParsing(boolean atomicImportParsing) {
    this.atomicImportParsing = atomicImportParsing;
  }

  public boolean isNoChecking() {
    return noChecking;
  }

  public void setNoChecking(boolean noChecking) {
    this.noChecking = noChecking;
  }

  public Path getTmpDir() {
    return tmpDir;
  }

  public void setTmpDir(Path tmpDir) {
    this.tmpDir = tmpDir;
  }

  public void addToIncludePath(Path p) {
    this.includePath.add(p);
  }
  
  public List<Path> getIncludePath() {
    return Collections.unmodifiableList(new ArrayList<>(includePath));
  }

  public void setIncludePath(List<Path> includePath) {
    this.includePath = includePath;
  }

  public RelativePath createCachePath(String relativePath) {
    return new RelativePath(cacheDir, relativePath);
  }
  
  public RelativePath createOutPath(String relativePath) {
    return new RelativePath(getBin(), relativePath);
  }

  public boolean isTerminateJVMAfterProcessing() {
    return terminateJVMAfterProcessing;
  }

  public void setTerminateJVMAfterProcessing(boolean terminateJVMAfterProcessing) {
    this.terminateJVMAfterProcessing = terminateJVMAfterProcessing;
  }
  
  public Stamper getStamper() {
    return stamper;
  }
}
