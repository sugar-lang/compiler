package org.sugarj.driver;

import org.sugarj.cleardep.build.BuildRequirement;

public class DriverBuildRequirement extends BuildRequirement<DriverInput, Result, Driver, DriverFactory> {
  private static final long serialVersionUID = -8894738588093775426L;

  public DriverBuildRequirement(DriverInput input) {
    super(DriverFactory.instance, input);
  }
}
