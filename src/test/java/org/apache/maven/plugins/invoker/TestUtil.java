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
import java.nio.file.Path;
import java.util.Map;

import org.apache.maven.api.Project;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.settings.Settings;
import org.codehaus.plexus.util.ReflectionUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestUtil {

    private TestUtil() {}

    static Project project(Model model, Path pom) {
        Project project = mock(Project.class);
        when(project.getModel()).thenReturn(model);
        when(project.getPomPath()).thenReturn(pom);
        when(project.getBasedir()).thenReturn(pom.getParent());
        return project;
    }

    static Project project(Map<String, String> properties) {
        return project(
                Model.newBuilder().properties(properties).build(), new File(getBasedir(), "target/foo.txt").toPath());
    }

    static Settings settings() {
        return Settings.newInstance();
    }

    static String getBasedir() {
        String path = System.getProperty("basedir");
        return path != null ? path : new File("").getAbsolutePath();
    }

    static void setVariableValueToObject(Object object, String field, Object value) throws IllegalAccessException {
        ReflectionUtils.setVariableValueInObject(object, field, value);
    }
}
