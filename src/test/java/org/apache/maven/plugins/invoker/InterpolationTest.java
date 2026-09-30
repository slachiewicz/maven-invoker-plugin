/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.invoker;

import java.io.File;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.api.Project;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Scm;
import org.apache.maven.api.plugin.Log;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author Olivier Lamy
 * @since 22 nov. 07
 */
class InterpolationTest {

    private Project buildMavenProjectStub() {
        Model model = Model.newBuilder()
                .version("1.0-SNAPSHOT")
                .artifactId("foo")
                .groupId("bar")
                .properties(Map.of("fooOnProject", "barOnProject"))
                .scm(Scm.newBuilder().connection("http://blabla").build())
                .build();
        return TestUtil.project(model, new File(TestUtil.getBasedir(), "pom.xml").toPath());
    }

    @Test
    void compositeMap() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("foo", "bar");
        properties.put("version", "2.0-SNAPSHOT");
        CompositeMap compositeMap = new CompositeMap(buildMavenProjectStub(), properties, false);
        assertThat(compositeMap).containsEntry("pom.version", "1.0-SNAPSHOT");
        assertThat(compositeMap).containsEntry("foo", "bar");
        assertThat(compositeMap).containsEntry("pom.groupId", "bar");
        assertThat(compositeMap).containsEntry("pom.scm.connection", "http://blabla");
        assertThat(compositeMap).containsEntry("fooOnProject", "barOnProject");
    }

    @Test
    void pomInterpolation() throws Exception {
        InvokerMojo invokerMojo = new InvokerMojo();
        TestUtil.setVariableValueToObject(invokerMojo, "project", buildMavenProjectStub());
        TestUtil.setVariableValueToObject(invokerMojo, "settings", TestUtil.settings());
        TestUtil.setVariableValueToObject(invokerMojo, "log", mock(Log.class));
        Properties properties = new Properties();
        properties.put("foo", "bar");
        properties.put("version", "2.0-SNAPSHOT");
        TestUtil.setVariableValueToObject(invokerMojo, "filterProperties", properties);
        String dirPath = TestUtil.getBasedir() + File.separatorChar + "src" + File.separatorChar + "test"
                + File.separatorChar + "resources" + File.separatorChar + "unit" + File.separatorChar + "interpolation";

        File interpolatedPomFile = new File(TestUtil.getBasedir(), "target/interpolated-pom.xml");
        invokerMojo.buildInterpolatedFile(new File(dirPath, "pom.xml"), interpolatedPomFile);
        try (Reader reader = new XmlStreamReader(interpolatedPomFile)) {
            String content = IOUtil.toString(reader);
            assertThat(content.indexOf("<interpolateValue>bar</interpolateValue>"))
                    .isPositive();
        }
        // recreate it to test delete if exists before creation
        invokerMojo.buildInterpolatedFile(new File(dirPath, "pom.xml"), interpolatedPomFile);
        try (Reader reader = new XmlStreamReader(interpolatedPomFile)) {
            String content = IOUtil.toString(reader);
            assertThat(content.indexOf("<interpolateValue>bar</interpolateValue>"))
                    .isPositive();
        }
    }
}
