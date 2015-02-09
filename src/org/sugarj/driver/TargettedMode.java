package org.sugarj.driver;

import org.sugarj.cleardep.CompilationUnit;
import org.sugarj.cleardep.Mode;
import org.sugarj.common.path.Path;

public interface TargettedMode<E extends CompilationUnit> extends Mode<E> {
  public abstract Path getTargetDir();
  public abstract boolean isTemporary();
}
