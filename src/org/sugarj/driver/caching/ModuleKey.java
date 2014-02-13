package org.sugarj.driver.caching;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import org.spoofax.interpreter.terms.IStrategoTerm;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.FileCommands;
import org.sugarj.common.deps.Stamper;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * The key of some SDF module as needed for caching.
 * 
 * Besides the actual SDF module, also the path for looking up
 * referenced modules is stored here. This way we do not need
 * to store a complete grammar, as represented by SDF definitions. 
 * 
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 *
 */
public class ModuleKey implements Externalizable {

  private boolean checkGet;
  
  public Map<String, Integer> moduleDeps;
  public String body;
  
  /**
   * For deserialization only.
   */
  public ModuleKey() {}
  
  public ModuleKey(Map<String, Integer> moduleDeps, String body) {
    this.moduleDeps = moduleDeps;
    this.body = body;
  }
  
  public ModuleKey(Stamper stamper, Set<Path> dependentFiles, Pattern pat, IStrategoTerm module) throws IOException {
    this.moduleDeps = new HashMap<>();
    
    this.body = ATermCommands.atermToString(module);
    
    for (Path p : dependentFiles)
      if (pat == null || pat.matcher(p.getAbsolutePath()).matches())
        if (FileCommands.exists(p)) {
          String cachePath;
          if (p instanceof RelativePath)
            cachePath = ((RelativePath) p).getRelativePath();
          else
            cachePath = p.getAbsolutePath();
          moduleDeps.put(cachePath, stamper.stampOf(p));
        }
  }
  
  public boolean equals(Object o) {
    if (!(o instanceof ModuleKey))
      return false;
    
    ModuleKey k = (ModuleKey) o;
    
    if (!body.equals(k.body))
      return false;
    
    return (checkGet ? moduleDeps.equals(k.moduleDeps) : moduleDeps.keySet().equals(k.moduleDeps.keySet()));
  }
  
  public int hashCode() {
    return moduleDeps.keySet().hashCode() + body.hashCode();
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    moduleDeps = new HashMap<>();
    int entries = in.readInt();
    for (int i = 0; i < entries; i++) {
      moduleDeps.put((String) in.readObject(), in.readInt());
    }
    
    body = (String) in.readObject();
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    out.writeInt(moduleDeps.size());
    for (Entry<String, Integer> entry : moduleDeps.entrySet()) {
      out.writeObject(entry.getKey());
      out.writeInt(entry.getValue());
    }
    
    out.writeObject(body);
  }

  void doGet() {
    this.checkGet = true;
  }

  void doPut() {
    this.checkGet = false;
  }
  
}
