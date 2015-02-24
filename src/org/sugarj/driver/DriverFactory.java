package org.sugarj.driver;

import org.sugarj.cleardep.build.BuildManager;
import org.sugarj.cleardep.build.BuilderFactory;

public class DriverFactory implements BuilderFactory<DriverInput, Result, Driver> {
  private static final long serialVersionUID = -8893296138351700814L;

  public final static DriverFactory instance = new DriverFactory();
  
  private DriverFactory() { }
  
  @Override
  public Driver makeBuilder(DriverInput input, BuildManager manager) {
    return new Driver(input, manager);
  }

}
