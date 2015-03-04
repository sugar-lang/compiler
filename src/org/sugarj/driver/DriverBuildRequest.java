package org.sugarj.driver;

import org.sugarj.cleardep.build.BuildRequest;

public class DriverBuildRequest extends BuildRequest<DriverInput, Result, Driver, DriverFactory> {
  private static final long serialVersionUID = -8894738588093775426L;

  public DriverBuildRequest(DriverInput input) {
    super(DriverFactory.instance, input);
  }
}
