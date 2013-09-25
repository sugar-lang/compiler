package org.sugarj.util;

import java.util.Iterator;
import java.util.List;

import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.Result;

/**
 * 
 * @author seba
 */
public abstract class ProcessingListener {
  public abstract void processingStarts(RelativePath sourceFile);
  public abstract void processingDone(Result result);
  
  public static void notifyProcessingStarts(Object owner, List<ProcessingListener> listeners, RelativePath sourceFile) {
    Iterator<ProcessingListener> it;
    synchronized (owner) {
      it = listeners.iterator();
    }
    for (;it.hasNext();)
      it.next().processingStarts(sourceFile);
  }
  
  public static void notifyProcessingDone(Object owner, List<ProcessingListener> listeners, Result result) {
    Iterator<ProcessingListener> it;
    synchronized (owner) {
      it = listeners.iterator();
    }
    for (;it.hasNext();)
      it.next().processingDone(result);
  }
}
