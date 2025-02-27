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
package io.trino.plugin.hive.metastore;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.plugin.hive.HideDeltaLakeTables;
import io.trino.plugin.hive.metastore.file.FileMetastoreModule;
import io.trino.plugin.hive.metastore.glue.GlueMetastoreModule;
import io.trino.plugin.hive.metastore.thrift.ThriftMetastoreModule;

import java.util.Optional;

import static io.airlift.configuration.ConditionalModule.conditionalModule;

public class HiveMetastoreModule
        extends AbstractConfigurationAwareModule
{
    private final Optional<HiveMetastore> metastore;

    public HiveMetastoreModule(Optional<HiveMetastore> metastore)
    {
        this.metastore = metastore;
    }

    @Override
    protected void setup(Binder binder)
    {
        if (metastore.isPresent()) {
            binder.bind(HiveMetastoreFactory.class).annotatedWith(RawHiveMetastoreFactory.class).toInstance(HiveMetastoreFactory.ofInstance(metastore.get()));
        }
        else {
            bindMetastoreModule("thrift", new ThriftMetastoreModule());
            bindMetastoreModule("file", new FileMetastoreModule());
            bindMetastoreModule("glue", new GlueMetastoreModule());
            // Load Alluxio metastore support through reflection. This makes Alluxio effectively an optional dependency
            // and allows deploying Trino without the Alluxio jar. Can be useful if the integration is unused and is flagged
            // by a security scanner.
            bindMetastoreModule("alluxio-deprecated", deferredModule("io.trino.plugin.hive.metastore.alluxio.AlluxioMetastoreModule"));
        }

        install(new DecoratedHiveMetastoreModule());
    }

    private void bindMetastoreModule(String name, Module module)
    {
        install(conditionalModule(
                MetastoreTypeConfig.class,
                metastore -> name.equalsIgnoreCase(metastore.getMetastoreType()),
                module));
    }

    private static Module deferredModule(String moduleClassName)
    {
        return new AbstractConfigurationAwareModule()
        {
            @Override
            protected void setup(Binder binder)
            {
                try {
                    install(Class.forName(moduleClassName)
                            .asSubclass(Module.class)
                            .getConstructor()
                            .newInstance());
                }
                catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Problem loading module class: " + moduleClassName, e);
                }
            }
        };
    }

    @HideDeltaLakeTables
    @Singleton
    @Provides
    public boolean hideDeltaLakeTables(HiveMetastoreConfig hiveMetastoreConfig)
    {
        return hiveMetastoreConfig.isHideDeltaLakeTables();
    }
}
