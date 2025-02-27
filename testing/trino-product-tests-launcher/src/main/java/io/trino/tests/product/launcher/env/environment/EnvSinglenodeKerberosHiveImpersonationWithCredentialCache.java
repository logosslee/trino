/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.tests.product.launcher.env.environment;

import com.google.common.collect.ImmutableList;
import io.trino.tests.product.launcher.docker.DockerFiles;
import io.trino.tests.product.launcher.docker.DockerFiles.ResourceProvider;
import io.trino.tests.product.launcher.env.Environment;
import io.trino.tests.product.launcher.env.EnvironmentProvider;
import io.trino.tests.product.launcher.env.common.HadoopKerberos;
import io.trino.tests.product.launcher.env.common.Standard;
import io.trino.tests.product.launcher.env.common.TestsEnvironment;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;
import static org.testcontainers.utility.MountableFile.forHostPath;

@TestsEnvironment
public final class EnvSinglenodeKerberosHiveImpersonationWithCredentialCache
        extends EnvironmentProvider
{
    private final ResourceProvider configDir;

    @Inject
    public EnvSinglenodeKerberosHiveImpersonationWithCredentialCache(DockerFiles dockerFiles, Standard standard, HadoopKerberos hadoopKerberos)
    {
        super(ImmutableList.of(standard, hadoopKerberos));
        configDir = requireNonNull(dockerFiles, "dockerFiles is null").getDockerFilesHostDirectory("conf/environment/singlenode-kerberos-hive-impersonation-with-credential-cache");
    }

    @Override
    @SuppressWarnings("resource")
    public void extendEnvironment(Environment.Builder builder)
    {
        builder.addConnector("hive", forHostPath(configDir.getPath("hive.properties")));
        builder.addConnector("iceberg", forHostPath(configDir.getPath("iceberg.properties")));
    }
}
