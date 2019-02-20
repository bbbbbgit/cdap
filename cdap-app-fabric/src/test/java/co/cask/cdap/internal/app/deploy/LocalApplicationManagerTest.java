/*
 * Copyright © 2014-2019 Cask Data, Inc.
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

package co.cask.cdap.internal.app.deploy;

import co.cask.cdap.AllProgramsApp;
import co.cask.cdap.ConfigTestApp;
import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.artifact.ArtifactId;
import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.api.artifact.ArtifactVersion;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.namespace.NamespaceAdmin;
import co.cask.cdap.common.test.AppJarHelper;
import co.cask.cdap.internal.AppFabricTestHelper;
import co.cask.cdap.internal.app.deploy.pipeline.AppDeploymentInfo;
import co.cask.cdap.internal.app.deploy.pipeline.ApplicationWithPrograms;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactDescriptor;
import co.cask.cdap.master.spi.program.ProgramDescriptor;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import com.google.gson.Gson;
import org.apache.twill.filesystem.LocalLocationFactory;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Tests the functionality of Deploy Manager.
 */
public class LocalApplicationManagerTest {
  @ClassRule
  public static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();
  public static final Gson GSON = new Gson();

  private static LocationFactory lf;

  @BeforeClass
  public static void before() throws Exception {
    lf = new LocalLocationFactory(TMP_FOLDER.newFolder());

    NamespaceAdmin namespaceAdmin = AppFabricTestHelper.getInjector().getInstance(NamespaceAdmin.class);
    namespaceAdmin.create(NamespaceMeta.DEFAULT);
  }

  /**
   * Improper Manifest file should throw an exception.
   */
  @Test(expected = ExecutionException.class)
  public void testImproperOrNoManifestFile() throws Exception {
    // Create an JAR without the MainClass set.
    File deployFile = TMP_FOLDER.newFile();
    try (JarOutputStream output = new JarOutputStream(new FileOutputStream(deployFile), new Manifest())) {
      output.putNextEntry(new JarEntry("dummy"));
    }

    Location jarLoc = Locations.toLocation(deployFile);

    ArtifactId artifactId = new ArtifactId("dummy", new ArtifactVersion("1.0.0-SNAPSHOT"), ArtifactScope.USER);
    ArtifactDescriptor artifactDescriptor = new ArtifactDescriptor(artifactId, jarLoc);

    AppDeploymentInfo info = new AppDeploymentInfo(artifactDescriptor, NamespaceId.DEFAULT,
                                                   "some.class.name", null, null, null);
    AppFabricTestHelper.getLocalManager().deploy(info).get();
  }

  /**
   * Good pipeline with good tests.
   */
  @Test
  public void testGoodPipeline() throws Exception {
    Location deployedJar = AppJarHelper.createDeploymentJar(lf, AllProgramsApp.class);
    ArtifactId artifactId = new ArtifactId("app", new ArtifactVersion("1.0.0-SNAPSHOT"), ArtifactScope.USER);
    ArtifactDescriptor artifactDescriptor = new ArtifactDescriptor(artifactId, deployedJar);
    AppDeploymentInfo info = new AppDeploymentInfo(artifactDescriptor, NamespaceId.DEFAULT,
                                                   AllProgramsApp.class.getName(), null, null, null);
    ApplicationWithPrograms input = AppFabricTestHelper.getLocalManager().deploy(info).get();

    ApplicationSpecification appSpec = Specifications.from(new AllProgramsApp());

    // Validate that all programs are being captured by the deployment pipeline
    Map<ProgramType, Set<String>> programByTypes = new HashMap<>();
    for (ProgramDescriptor desc : input.getPrograms()) {
      ProgramId programId = desc.getProgramId();
      programByTypes.computeIfAbsent(programId.getType(), k -> new HashSet<>()).add(programId.getProgram());
    }
    for (co.cask.cdap.api.app.ProgramType programType : co.cask.cdap.api.app.ProgramType.values()) {
      Assert.assertEquals(appSpec.getProgramsByType(programType),
                          programByTypes.getOrDefault(ProgramType.valueOf(programType.name()), Collections.emptySet()));
    }
  }

  @Test
  public void testValidConfigPipeline() throws Exception {
    Location deployedJar = AppJarHelper.createDeploymentJar(lf, ConfigTestApp.class);
    ConfigTestApp.ConfigClass config = new ConfigTestApp.ConfigClass("myTable");
    ArtifactId artifactId = new ArtifactId("configtest", new ArtifactVersion("1.0.0-SNAPSHOT"), ArtifactScope.USER);
    ArtifactDescriptor artifactDescriptor = new ArtifactDescriptor(artifactId, deployedJar);
    AppDeploymentInfo info = new AppDeploymentInfo(artifactDescriptor, NamespaceId.DEFAULT,
                                                   ConfigTestApp.class.getName(), "MyApp", null, GSON.toJson(config));
    AppFabricTestHelper.getLocalManager().deploy(info).get();
  }

  @Test(expected = ExecutionException.class)
  public void testInvalidConfigPipeline() throws Exception {
    Location deployedJar = AppJarHelper.createDeploymentJar(lf, ConfigTestApp.class);
    ArtifactId artifactId = new ArtifactId("configtest", new ArtifactVersion("1.0.0-SNAPSHOT"), ArtifactScope.USER);
    ArtifactDescriptor artifactDescriptor = new ArtifactDescriptor(artifactId, deployedJar);
    AppDeploymentInfo info = new AppDeploymentInfo(artifactDescriptor, NamespaceId.DEFAULT,
                                                   ConfigTestApp.class.getName(), "BadApp", null,
                                                   GSON.toJson("invalid"));
    AppFabricTestHelper.getLocalManager().deploy(info).get();
  }
}
