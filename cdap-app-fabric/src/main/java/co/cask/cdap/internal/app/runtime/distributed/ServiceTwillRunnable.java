/*
 * Copyright © 2014-2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.distributed;

import co.cask.cdap.app.guice.DistributedArtifactManagerModule;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.data.runtime.SystemDatasetRuntimeModule;
import co.cask.cdap.internal.app.runtime.service.ServiceProgramRunner;
import co.cask.cdap.master.spi.program.ProgramOptions;
import co.cask.cdap.proto.id.ProgramRunId;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import org.apache.hadoop.conf.Configuration;

/**
 * A TwillRunnable for running Service components in distributed mode.
 */
public class ServiceTwillRunnable extends AbstractProgramTwillRunnable<ServiceProgramRunner> {

  protected ServiceTwillRunnable(String name) {
    super(name);
  }

  @Override
  protected Module createModule(CConfiguration cConf, Configuration hConf,
                                ProgramOptions programOptions, ProgramRunId programRunId) {
    Module module = super.createModule(cConf, hConf, programOptions, programRunId);
    return Modules.combine(module,
                           new DistributedArtifactManagerModule(),
                           new SystemDatasetRuntimeModule().getDistributedModules());
  }
}
