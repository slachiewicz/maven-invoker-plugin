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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.xml.ModelXmlFactory;

/**
 * Installs the project artifacts of the main build into the local repository as a preparation to run the sub projects.
 * More precisely, all artifacts of the project itself, all its locally reachable parent POMs and all its dependencies
 * from the reactor will be installed to the local repository.
 *
 * @author Paul Gier
 * @author Benjamin Bentmann
 * @since 1.2
 */
@Mojo(name = "install", defaultPhase = "pre-integration-test")
public class InstallMojo implements org.apache.maven.api.plugin.Mojo {

    @Inject
    private Log log;

    @Inject
    private Session session;

    @Inject
    private Project project;

    /**
     * The path to the local repository into which the project artifacts should be installed for the integration tests.
     * If not set, the regular local repository will be used. To prevent soiling of your regular local repository with
     * possibly broken artifacts, it is strongly recommended to use an isolated repository for the integration tests
     * (e.g. <code>${project.build.directory}/it-repo</code>).
     */
    @Parameter(property = "invoker.localRepositoryPath")
    private File localRepositoryPath;

    /**
     * A flag used to disable the installation procedure. This is primarily intended for usage from the command line to
     * occasionally adjust the build.
     *
     * @since 1.4
     */
    @Parameter(property = "invoker.skip", defaultValue = "false")
    private boolean skipInstallation;

    /**
     * Extra dependencies that need to be installed on the local repository.
     * <p>
     * Format:
     * <pre>
     * groupId:artifactId:version:type:classifier
     * </pre>
     * <p>
     * Examples:
     * <pre>
     * org.apache.maven.plugins:maven-clean-plugin:2.4:maven-plugin
     * org.apache.maven.plugins:maven-clean-plugin:2.4:jar:javadoc
     * </pre>
     * <p>
     * If the type is 'maven-plugin' the plugin will try to resolve the artifact using plugin remote repositories,
     * instead of using artifact remote repositories.
     * <p>
     * <b>NOTICE</b> all dependencies will be resolved with transitive dependencies in <code>runtime</code> scope.
     *
     * @since 1.6
     */
    @Parameter
    private String[] extraArtifacts;

    /**
     * Scope to resolve project artifacts. One of <code>compile</code>, <code>runtime</code>, <code>test</code>, or a
     * Maven 4 path scope id (<code>main-compile</code>, <code>main-runtime</code>, <code>test-compile</code>,
     * <code>test-runtime</code>).
     *
     * @since 3.5.0
     */
    @Parameter(property = "invoker.install.scope", defaultValue = "runtime")
    private String scope;

    private ModelXmlFactory modelXmlFactory;

    /**
     * Performs this mojo's tasks.
     *
     * @throws MojoException If the artifacts could not be installed.
     */
    @Override
    public void execute() throws MojoException {
        if (skipInstallation) {
            log.info("Skipping artifact installation per configuration.");
            return;
        }

        modelXmlFactory = session.getService(ModelXmlFactory.class);
        Map<String, Resolved> resolvedArtifacts = new LinkedHashMap<>();

        resolveProjectArtifacts(resolvedArtifacts);
        resolveProjectPoms(project, resolvedArtifacts);
        resolveProjectDependencies(resolvedArtifacts);
        resolveExtraArtifacts(resolvedArtifacts);
        installArtifacts(resolvedArtifacts);
    }

    /**
     * An artifact together with the file that holds its content.
     */
    private static final class Resolved {
        final Artifact artifact;
        final Path path;

        Resolved(Artifact artifact, Path path) {
            this.artifact = artifact;
            this.path = path;
        }
    }

    private static String key(Artifact a) {
        return a.getGroupId()
                + ':'
                + a.getArtifactId()
                + ':'
                + a.getExtension()
                + ':'
                + a.getClassifier()
                + ':'
                + a.getVersion();
    }

    private void put(Map<String, Resolved> resolvedArtifacts, Artifact artifact, Path path) {
        resolvedArtifacts.put(key(artifact), new Resolved(artifact, path));
    }

    private void resolveProjectArtifacts(Map<String, Resolved> resolvedArtifacts) {
        ProjectManager projectManager = session.getService(ProjectManager.class);

        // pom packaging doesn't have a main artifact
        Optional<ProducedArtifact> mainArtifact = project.getMainArtifact();
        mainArtifact.ifPresent(a -> session.getArtifactPath(a).ifPresent(path -> put(resolvedArtifacts, a, path)));

        for (ProducedArtifact attached : projectManager.getAttachedArtifacts(project)) {
            session.getArtifactPath(attached).ifPresent(path -> put(resolvedArtifacts, attached, path));
        }
    }

    private void resolveProjectPoms(Project project, Map<String, Resolved> resolvedArtifacts) {
        put(resolvedArtifacts, project.getPomArtifact(), project.getPomPath());

        // Project#getParent only knows parents that are part of the reactor, so walk the parent chain of the model
        Parent parent = project.getModel().getParent();
        if (parent != null) {
            List<RemoteRepository> repositories =
                    session.getService(ProjectManager.class).getRemoteProjectRepositories(project);
            resolvePomWithParents(
                    resolvePom(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), repositories),
                    resolvedArtifacts,
                    repositories);
        }
    }

    private void resolveProjectDependencies(Map<String, Resolved> resolvedArtifacts) {
        DependencyResolverResult result = session.getService(DependencyResolver.class)
                .resolve(DependencyResolverRequest.builder()
                        .session(session)
                        .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                        .project(project)
                        .pathScope(toPathScope(scope))
                        .build());

        List<Resolved> artifacts = new ArrayList<>();
        result.getDependencies().forEach((dependency, path) -> artifacts.add(new Resolved(dependency, path)));
        artifacts.forEach(a -> put(resolvedArtifacts, a.artifact, a.path));
        resolvePomsForArtifacts(
                artifacts.stream().map(a -> a.artifact).collect(Collectors.toList()),
                resolvedArtifacts,
                session.getService(ProjectManager.class).getRemoteProjectRepositories(project));
    }

    private PathScope toPathScope(String scope) {
        switch (scope) {
            case "compile":
                return PathScope.MAIN_COMPILE;
            case "runtime":
                return PathScope.MAIN_RUNTIME;
            case "test":
                return PathScope.TEST_RUNTIME;
            default:
                return session.requirePathScope(scope);
        }
    }

    /**
     * Resolve extra artifacts.
     */
    private void resolveExtraArtifacts(Map<String, Resolved> resolvedArtifacts) {
        if (extraArtifacts == null) {
            return;
        }

        ProjectManager projectManager = session.getService(ProjectManager.class);

        for (String extraArtifact : extraArtifacts) {
            String[] gav = extraArtifact.split(":");
            if (gav.length < 3 || gav.length > 5) {
                throw new MojoException("Invalid artifact " + extraArtifact);
            }

            String groupId = gav[0];
            String artifactId = gav[1];
            String version = gav[2];

            String type = "jar";
            if (gav.length > 3) {
                type = gav[3];
            }

            String classifier = null;
            if (gav.length == 5) {
                classifier = gav[4];
            }

            Type artifactType = session.requireType(type);

            List<RemoteRepository> remoteRepositories = Type.MAVEN_PLUGIN.equals(artifactType.id())
                    ? projectManager.getRemotePluginRepositories(project)
                    : projectManager.getRemoteProjectRepositories(project);

            DependencyCoordinates root = session.createDependencyCoordinates(session.createArtifactCoordinates(
                    groupId, artifactId, version, classifier, artifactType.getExtension(), type));

            resolvePomWithParents(
                    resolvePom(groupId, artifactId, version, remoteRepositories),
                    resolvedArtifacts,
                    remoteRepositories);

            DependencyResolverResult result = session.getService(DependencyResolver.class)
                    .resolve(DependencyResolverRequest.builder()
                            .session(session)
                            .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                            .dependencies(List.of(root))
                            .pathScope(PathScope.MAIN_RUNTIME)
                            .repositories(remoteRepositories)
                            .build());

            List<Resolved> artifacts = new ArrayList<>();
            result.getDependencies().forEach((dependency, path) -> artifacts.add(new Resolved(dependency, path)));
            artifacts.forEach(a -> put(resolvedArtifacts, a.artifact, a.path));
            resolvePomsForArtifacts(
                    artifacts.stream().map(a -> a.artifact).collect(Collectors.toList()),
                    resolvedArtifacts,
                    remoteRepositories);
        }
    }

    private void resolvePomsForArtifacts(
            Collection<Artifact> artifacts,
            Map<String, Resolved> resolvedArtifacts,
            List<RemoteRepository> remoteRepositories) {
        for (Artifact a : artifacts) {
            DownloadedArtifact pom =
                    resolvePom(a.getGroupId(), a.getArtifactId(), a.getVersion().toString(), remoteRepositories);
            resolvePomWithParents(pom, resolvedArtifacts, remoteRepositories);
        }
    }

    private void resolvePomWithParents(
            DownloadedArtifact artifact, Map<String, Resolved> resolvedArtifacts, List<RemoteRepository> repositories) {

        if (resolvedArtifacts.containsKey(key(artifact))) {
            return;
        }

        Model model = PomUtils.loadPom(modelXmlFactory, artifact.getPath());
        Parent parent = model.getParent();
        if (parent != null) {
            DownloadedArtifact resolvedPom =
                    resolvePom(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), repositories);
            resolvePomWithParents(resolvedPom, resolvedArtifacts, repositories);
        }

        put(resolvedArtifacts, artifact, artifact.getPath());
    }

    private DownloadedArtifact resolvePom(
            String groupId, String artifactId, String version, List<RemoteRepository> remoteRepositories) {
        return session.resolveArtifact(
                session.createArtifactCoordinates(groupId, artifactId, version, "pom"), remoteRepositories);
    }

    /**
     * Install list of artifacts into local repository.
     */
    private void installArtifacts(Map<String, Resolved> resolvedArtifacts) {

        Session sessionForLocalRepo = createSessionForLocalRepo();

        // we can have on dependency two artifacts with the same groupId:artifactId
        // with different version, in such case when we install both in one request
        // metadata will contain only one version

        Map<String, List<Resolved>> collect = resolvedArtifacts.values().stream()
                .filter(a -> !hasTheSamePathAsTarget(a, sessionForLocalRepo))
                .collect(Collectors.groupingBy(
                        a -> String.format(
                                "%s:%s:%s",
                                a.artifact.getGroupId(), a.artifact.getArtifactId(), a.artifact.getVersion()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        // Artifacts of reactor projects must be installed as they are: Session#setArtifactPath rewrites the file of
        // every project artifact with the same id, and throws for the transformed (consumer) POM.
        Map<String, ProducedArtifact> reactorArtifacts = new LinkedHashMap<>();
        ProjectManager projectManager = session.getService(ProjectManager.class);
        for (Project reactorProject : session.getProjects()) {
            reactorProject.getArtifacts().forEach(a -> reactorArtifacts.put(key(a), a));
            projectManager.getAttachedArtifacts(reactorProject).forEach(a -> reactorArtifacts.put(key(a), a));
        }

        for (List<Resolved> artifacts : collect.values()) {
            List<ProducedArtifact> produced = new ArrayList<>();
            for (Resolved resolved : artifacts) {
                Artifact a = resolved.artifact;
                ProducedArtifact p = reactorArtifacts.get(key(a));
                if (p == null) {
                    p = sessionForLocalRepo.createProducedArtifact(
                            a.getGroupId(),
                            a.getArtifactId(),
                            a.getVersion().toString(),
                            a.getClassifier(),
                            a.getExtension(),
                            null);
                    sessionForLocalRepo.setArtifactPath(p, resolved.path);
                }
                produced.add(p);
            }
            sessionForLocalRepo.installArtifacts(produced);
        }
    }

    private boolean hasTheSamePathAsTarget(Resolved resolved, Session sessionForLocalRepo) {
        try {
            Path targetFile = sessionForLocalRepo.getPathForLocalArtifact(resolved.artifact);
            if (targetFile == null || !Files.exists(targetFile)) {
                return false;
            }
            if (Files.isSameFile(targetFile, resolved.path)) {
                log.debug("Skip install the same target " + resolved.path);
                return true;
            }
            return false;
        } catch (IOException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    /**
     * Create a new {@link Session} connected with the local repo that should receive the artifacts.
     */
    private Session createSessionForLocalRepo() {
        if (localRepositoryPath == null) {
            return session;
        }
        Session newSession = session.withLocalRepository(session.createLocalRepository(localRepositoryPath.toPath()));
        log.debug("localRepoPath: " + newSession.getLocalRepository().getPath());
        return newSession;
    }
}
