/*
 * Copyright © 2016-2019 Cask Data, Inc.
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

package co.cask.cdap.app.runtime.spark;

import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.metadata.MetadataReader;
import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.api.security.store.SecureStore;
import co.cask.cdap.api.security.store.SecureStoreManager;
import co.cask.cdap.api.spark.Spark;
import co.cask.cdap.api.spark.SparkSpecification;
import co.cask.cdap.app.runtime.ProgramClassLoaderProvider;
import co.cask.cdap.app.runtime.ProgramRunner;
import co.cask.cdap.app.runtime.spark.submit.DistributedSparkSubmitter;
import co.cask.cdap.app.runtime.spark.submit.LocalSparkSubmitter;
import co.cask.cdap.app.runtime.spark.submit.SparkSubmitter;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.lang.FilterClassLoader;
import co.cask.cdap.common.lang.InstantiatorFactory;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.data.ProgramContextAware;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.metadata.writer.FieldLineageWriter;
import co.cask.cdap.data2.metadata.writer.MetadataPublisher;
import co.cask.cdap.internal.app.runtime.AbstractProgramRunnerWithPlugin;
import co.cask.cdap.internal.app.runtime.BasicProgramContext;
import co.cask.cdap.internal.app.runtime.ProgramOptionConstants;
import co.cask.cdap.internal.app.runtime.ProgramRunners;
import co.cask.cdap.internal.app.runtime.artifact.PluginFinder;
import co.cask.cdap.internal.app.runtime.plugin.PluginInstantiator;
import co.cask.cdap.internal.app.runtime.workflow.NameMappedDatasetFramework;
import co.cask.cdap.internal.app.runtime.workflow.WorkflowProgramInfo;
import co.cask.cdap.master.spi.program.Arguments;
import co.cask.cdap.master.spi.program.Program;
import co.cask.cdap.master.spi.program.ProgramController;
import co.cask.cdap.master.spi.program.ProgramOptions;
import co.cask.cdap.messaging.MessagingService;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.security.spi.authentication.AuthenticationContext;
import co.cask.cdap.security.spi.authorization.AuthorizationEnforcer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.io.Closeables;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.inject.Inject;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.tephra.TransactionSystemClient;
import org.apache.twill.api.RunId;
import org.apache.twill.api.ServiceAnnouncer;
import org.apache.twill.common.Threads;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The {@link ProgramRunner} that executes Spark program.
 */
@VisibleForTesting
public final class SparkProgramRunner extends AbstractProgramRunnerWithPlugin
                                      implements ProgramClassLoaderProvider, Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(SparkProgramRunner.class);

  private final CConfiguration cConf;
  private final Configuration hConf;
  private final LocationFactory locationFactory;
  private final TransactionSystemClient txClient;
  private final DatasetFramework datasetFramework;
  private final MetricsCollectionService metricsCollectionService;
  private final DiscoveryServiceClient discoveryServiceClient;
  private final SecureStore secureStore;
  private final SecureStoreManager secureStoreManager;
  private final AuthorizationEnforcer authorizationEnforcer;
  private final AuthenticationContext authenticationContext;
  private final MessagingService messagingService;
  private final ServiceAnnouncer serviceAnnouncer;
  private final PluginFinder pluginFinder;
  private final MetadataReader metadataReader;
  private final FieldLineageWriter fieldLineageWriter;
  private final MetadataPublisher metadataPublisher;
  private final NamespaceQueryAdmin namespaceQueryAdmin;

  @Inject
  SparkProgramRunner(CConfiguration cConf, Configuration hConf, LocationFactory locationFactory,
                     TransactionSystemClient txClient, DatasetFramework datasetFramework,
                     MetricsCollectionService metricsCollectionService,
                     DiscoveryServiceClient discoveryServiceClient,
                     SecureStore secureStore, SecureStoreManager secureStoreManager,
                     AuthorizationEnforcer authorizationEnforcer, AuthenticationContext authenticationContext,
                     MessagingService messagingService, ServiceAnnouncer serviceAnnouncer,
                     PluginFinder pluginFinder, MetadataReader metadataReader, MetadataPublisher metadataPublisher,
                     FieldLineageWriter fieldLineageWriter, NamespaceQueryAdmin namespaceQueryAdmin) {
    super(cConf);
    this.cConf = cConf;
    this.hConf = hConf;
    this.locationFactory = locationFactory;
    this.txClient = txClient;
    this.datasetFramework = datasetFramework;
    this.metricsCollectionService = metricsCollectionService;
    this.discoveryServiceClient = discoveryServiceClient;
    this.secureStore = secureStore;
    this.secureStoreManager = secureStoreManager;
    this.authorizationEnforcer = authorizationEnforcer;
    this.authenticationContext = authenticationContext;
    this.messagingService = messagingService;
    this.serviceAnnouncer = serviceAnnouncer;
    this.pluginFinder = pluginFinder;
    this.metadataReader = metadataReader;
    this.fieldLineageWriter = fieldLineageWriter;
    this.metadataPublisher = metadataPublisher;
    this.namespaceQueryAdmin = namespaceQueryAdmin;
  }

  @Override
  public ProgramController run(Program program, ProgramOptions options) {
    LOG.trace("Starting Spark program {} with SparkProgramRunner of ClassLoader {}",
              program.getId(), getClass().getClassLoader());

    // Get the RunId first. It is used for the creation of the ClassLoader closing thread.
    Arguments arguments = options.getArguments();
    RunId runId = ProgramRunners.getRunId(options);

    Deque<Closeable> closeables = new LinkedList<>();

    try {
      // Extract and verify parameters
      ApplicationSpecification appSpec = program.getApplicationSpecification();
      Preconditions.checkNotNull(appSpec, "Missing application specification.");

      ProgramType processorType = program.getType();
      Preconditions.checkNotNull(processorType, "Missing processor type.");
      Preconditions.checkArgument(processorType == ProgramType.SPARK, "Only Spark process type is supported.");

      SparkSpecification spec = appSpec.getSpark().get(program.getName());
      Preconditions.checkNotNull(spec, "Missing SparkSpecification for %s", program.getName());

      String host = options.getArguments().getOption(ProgramOptionConstants.HOST);
      Preconditions.checkArgument(host != null, "No hostname is provided");

      // Get the WorkflowProgramInfo if it is started by Workflow
      WorkflowProgramInfo workflowInfo = WorkflowProgramInfo.create(arguments);
      DatasetFramework programDatasetFramework = workflowInfo == null ?
        datasetFramework :
        NameMappedDatasetFramework.createFromWorkflowProgramInfo(datasetFramework, workflowInfo, appSpec);

      // Setup dataset framework context, if required
      if (programDatasetFramework instanceof ProgramContextAware) {
        ProgramId programId = program.getId();
        ((ProgramContextAware) programDatasetFramework).setContext(new BasicProgramContext(programId.run(runId)));
      }

      PluginInstantiator pluginInstantiator = createPluginInstantiator(options, program.getClassLoader());
      if (pluginInstantiator != null) {
        closeables.addFirst(pluginInstantiator);
      }

      SparkRuntimeContext runtimeContext = new SparkRuntimeContext(new Configuration(hConf), program, options, cConf,
                                                                   host, txClient, programDatasetFramework,
                                                                   discoveryServiceClient,
                                                                   metricsCollectionService, workflowInfo,
                                                                   pluginInstantiator, secureStore, secureStoreManager,
                                                                   authorizationEnforcer, authenticationContext,
                                                                   messagingService, serviceAnnouncer, pluginFinder,
                                                                   locationFactory, metadataReader, metadataPublisher,
                                                                   namespaceQueryAdmin);
      closeables.addFirst(runtimeContext);

      Spark spark;
      try {
        spark = new InstantiatorFactory(false).get(TypeToken.of(program.<Spark>getMainClass())).create();
      } catch (Exception e) {
        LOG.error("Failed to instantiate Spark class for {}", spec.getClassName(), e);
        throw Throwables.propagate(e);
      }

      boolean isLocal = SparkRuntimeContextConfig.isLocal(options);
      SparkSubmitter submitter = isLocal
        ? new LocalSparkSubmitter()
        : new DistributedSparkSubmitter(hConf, locationFactory, host, runtimeContext,
                                        options.getArguments().getOption(Constants.AppFabric.APP_SCHEDULER_QUEUE));

      Service sparkRuntimeService = new SparkRuntimeService(cConf, spark, getPluginArchive(options),
                                                            runtimeContext, submitter, locationFactory, isLocal,
                                                            fieldLineageWriter);

      sparkRuntimeService.addListener(createRuntimeServiceListener(closeables), Threads.SAME_THREAD_EXECUTOR);
      ProgramController controller = new SparkProgramController(sparkRuntimeService, runtimeContext);

      LOG.debug("Starting Spark Job. Context: {}", runtimeContext);
      if (isLocal || UserGroupInformation.isSecurityEnabled()) {
        sparkRuntimeService.start();
      } else {
        ProgramRunners.startAsUser(cConf.get(Constants.CFG_HDFS_USER), sparkRuntimeService);
      }
      return controller;
    } catch (Throwable t) {
      closeAllQuietly(closeables);
      throw Throwables.propagate(t);
    }
  }

  @Override
  public ClassLoader createProgramClassLoaderParent() {
    return new FilterClassLoader(getClass().getClassLoader(), SparkResourceFilters.SPARK_PROGRAM_CLASS_LOADER_FILTER);
  }

  /**
   * Closes the ClassLoader of this {@link SparkProgramRunner}. The
   * ClassLoader needs to be closed because there is one such ClassLoader created per program execution by
   * the {@link SparkProgramRuntimeProvider} to support concurrent Spark program execution in the same JVM.
   */
  @Override
  public void close() throws IOException {
    final ClassLoader classLoader = getClass().getClassLoader();
    Thread t = new Thread("spark-program-runner-delay-close") {
      @Override
      public void run() {
        // Delay the closing of the ClassLoader because Spark, which uses akka, has an async cleanup process
        // for shutting down threads. During shutdown, there are new classes being loaded.
        Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
        if (classLoader instanceof Closeable) {
          Closeables.closeQuietly((Closeable) classLoader);
          LOG.trace("Closed SparkProgramRunner ClassLoader {}", classLoader);
        }
      }
    };
    t.setDaemon(true);
    t.start();
  }

  @Nullable
  private File getPluginArchive(ProgramOptions options) {
    if (!options.getArguments().hasOption(ProgramOptionConstants.PLUGIN_ARCHIVE)) {
      return null;
    }
    return new File(options.getArguments().getOption(ProgramOptionConstants.PLUGIN_ARCHIVE));
  }
}
