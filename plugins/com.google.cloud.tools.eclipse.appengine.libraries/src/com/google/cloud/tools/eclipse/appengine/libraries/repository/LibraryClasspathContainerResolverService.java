/*
 * Copyright 2016 Google Inc.
 *
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

package com.google.cloud.tools.eclipse.appengine.libraries.repository;

import com.google.cloud.tools.eclipse.appengine.libraries.ILibraryClasspathContainerResolverService;
import com.google.cloud.tools.eclipse.appengine.libraries.LibraryClasspathContainer;
import com.google.cloud.tools.eclipse.appengine.libraries.Messages;
import com.google.cloud.tools.eclipse.appengine.libraries.SourceAttacherJob;
import com.google.cloud.tools.eclipse.appengine.libraries.model.Filter;
import com.google.cloud.tools.eclipse.appengine.libraries.model.Library;
import com.google.cloud.tools.eclipse.appengine.libraries.model.LibraryFactory;
import com.google.cloud.tools.eclipse.appengine.libraries.model.LibraryFactoryException;
import com.google.cloud.tools.eclipse.appengine.libraries.model.LibraryFile;
import com.google.cloud.tools.eclipse.appengine.libraries.persistence.LibraryClasspathContainerSerializer;
import com.google.cloud.tools.eclipse.util.status.StatusUtil;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.maven.artifact.Artifact;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jst.j2ee.classpathdep.UpdateClasspathAttributeUtil;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component
public class LibraryClasspathContainerResolverService 
                                       implements ILibraryClasspathContainerResolverService {

  private static final String CLASSPATH_ATTRIBUTE_SOURCE_URL =
      "com.google.cloud.tools.eclipse.appengine.libraries.sourceUrl";

  private static final Logger logger =
      Logger.getLogger(LibraryClasspathContainerResolverService.class.getName());

  private ILibraryRepositoryService repositoryService;
  private IExtensionRegistry extensionRegistry;
  private LibraryFactory libraryFactory;
  private LibraryClasspathContainerSerializer serializer;
  private Map<String, Library> libraries;

  public IStatus resolveAll(IJavaProject javaProject, IProgressMonitor monitor) {
    IStatus status = null;
    try {
      IClasspathEntry[] rawClasspath = javaProject.getRawClasspath();
      SubMonitor subMonitor = SubMonitor.convert(monitor,
                                                 Messages.getString("TaskResolveLibraries"),
                                                 getTotalwork(rawClasspath));
      for (IClasspathEntry classpathEntry : rawClasspath) {
        if (classpathEntry.getPath().segment(0).equals(Library.CONTAINER_PATH_PREFIX)) {
          status = StatusUtil.merge(status, resolveContainer(javaProject,
                                                             classpathEntry.getPath(),
                                                             subMonitor.newChild(1)));
        }
      }
    } catch (CoreException ex) {
      return StatusUtil.error(this, Messages.getString("TaskResolveLibrariesError"), ex);
    }
    return status == null ? Status.OK_STATUS : status;
  }

  public IClasspathEntry[] resolveLibraryAttachSourcesSync(String libraryId) throws CoreException {
    Library library = libraries.get(libraryId);
    if (library != null) {
      IClasspathEntry[] resolvedEntries = new IClasspathEntry[library.getLibraryFiles().size()];
      int idx = 0;
      for (LibraryFile libraryFile : library.getLibraryFiles()) {
        resolvedEntries[idx++] = resolveLibraryFileAttachSourceSync(libraryFile);
      }
      return resolvedEntries;
    } else {
      throw new CoreException(StatusUtil.error(this, Messages.getString("InvalidLibraryId",
                                                                        libraryId)));
    }
  }

  public IStatus resolveContainer(IJavaProject javaProject, IPath containerPath, 
                                  IProgressMonitor monitor) {
    Preconditions.checkArgument(containerPath.segment(0).equals(Library.CONTAINER_PATH_PREFIX));
    try {
      String libraryId = containerPath.segment(1);
      Library library = libraries.get(libraryId);
      if (library != null) {
        List<Job> sourceAttacherJobs = new ArrayList<>();
        LibraryClasspathContainer container = resolveLibraryFiles(javaProject, containerPath,
                                                                  library, sourceAttacherJobs,
                                                                  monitor);
        JavaCore.setClasspathContainer(containerPath,
                                       new IJavaProject[] {javaProject},
                                       new IClasspathContainer[] {container},
                                       new NullProgressMonitor());
        serializer.saveContainer(javaProject, container);
        for (Job job : sourceAttacherJobs) {
          job.schedule();
        }
      }
      return Status.OK_STATUS;
    } catch (CoreException | IOException ex) {
      return StatusUtil.error(this, Messages.getString("TaskResolveContainerError", containerPath), ex);
    }
  }

  public IStatus checkRuntimeAvailability(AppEngineRuntime runtime, IProgressMonitor monitor) {
    switch (runtime) {
      case STANDARD_JAVA_7:
        return checkAppEngineStandardJava7(monitor);
      default:
        throw new IllegalArgumentException("Unhandled runtime: " + runtime);
    }
  }

  private IStatus checkAppEngineStandardJava7(IProgressMonitor monitor) {
    try {
      for (String libraryId : new String[]{ "servlet-api", "jsp-api"}) {
        Library library = libraries.get(libraryId);
        for (LibraryFile libraryFile : library.getLibraryFiles()) {
          if (monitor.isCanceled()) {
            return Status.CANCEL_STATUS;
          }
          repositoryService.makeArtifactAvailable(libraryFile, monitor);
        }
      }
      return Status.OK_STATUS;
    } catch (CoreException ex) {
      return StatusUtil.error(this, Messages.getString("LibraryUnavailable"), ex);
    }
  }

  private LibraryClasspathContainer resolveLibraryFiles(IJavaProject javaProject,
                                                        IPath containerPath,
                                                        Library library,
                                                        List<Job> sourceAttacherJobs,
                                                        IProgressMonitor monitor) 
                                                            throws CoreException {
    List<LibraryFile> libraryFiles = library.getLibraryFiles();
    SubMonitor subMonitor = SubMonitor.convert(monitor, libraryFiles.size());
    subMonitor.subTask(Messages.getString("TaskResolveArtifacts", getLibraryDescription(library)));
    SubMonitor child = subMonitor.newChild(libraryFiles.size());

    IClasspathEntry[] entries = new IClasspathEntry[libraryFiles.size()];
    int idx = 0;
    for (final LibraryFile libraryFile : libraryFiles) {
      IClasspathEntry newLibraryEntry =
          resolveLibraryFileAttachSourceAsync(javaProject, containerPath, libraryFile,
                                              sourceAttacherJobs, monitor);
      entries[idx++] = newLibraryEntry;
      child.worked(1);
    }
    monitor.done();
    LibraryClasspathContainer container =
        new LibraryClasspathContainer(containerPath,
                                      getLibraryDescription(library), entries);
    return container;
  }

  private IClasspathEntry resolveLibraryFileAttachSourceAsync(IJavaProject javaProject,
                                                              IPath containerPath,
                                                              final LibraryFile libraryFile,
                                                              List<Job> sourceAttacherJobs,
                                                              final IProgressMonitor monitor)
                                                                  throws CoreException {
    final Artifact artifact = repositoryService.resolveArtifact(libraryFile, monitor);
    IPath libraryPath = new Path(artifact.getFile().getAbsolutePath());
    IPath sourceAttachmentPath = null;
    Job job = createSourceAttacherJob(javaProject, containerPath, libraryFile,
                                      monitor, artifact, libraryPath);
    sourceAttacherJobs.add(job);
    final IClasspathEntry newLibraryEntry =
        JavaCore.newLibraryEntry(libraryPath,
                                 sourceAttachmentPath,
                                 null /*  sourceAttachmentRootPath */,
                                 getAccessRules(libraryFile.getFilters()),
                                 getClasspathAttributes(libraryFile, artifact),
                                 true /* isExported */);
    return newLibraryEntry;
  }

  private SourceAttacherJob createSourceAttacherJob(IJavaProject javaProject,
                                                    IPath containerPath,
                                                    final LibraryFile libraryFile,
                                                    final IProgressMonitor monitor,
                                                    final Artifact artifact,
                                                    IPath libraryPath) {
    return new SourceAttacherJob(javaProject, containerPath, libraryPath, new Callable<IPath>() {
      @Override
      public IPath call() throws Exception {
          return repositoryService.resolveSourceArtifact(libraryFile, artifact.getVersion(),
                                                         monitor);
      }
    });
  }

  private IClasspathEntry resolveLibraryFileAttachSourceSync(final LibraryFile libraryFile)
                                                                              throws CoreException {
    final Artifact artifact = repositoryService.resolveArtifact(libraryFile, new NullProgressMonitor());
    IPath libraryPath = new Path(artifact.getFile().getAbsolutePath());
    IPath sourceAttachmentPath = null;
    sourceAttachmentPath = repositoryService.resolveSourceArtifact(libraryFile,
                                                                   artifact.getVersion(),
                                                                   new NullProgressMonitor());
    final IClasspathEntry newLibraryEntry =
        JavaCore.newLibraryEntry(libraryPath,
                                 sourceAttachmentPath,
                                 null /*  sourceAttachmentRootPath */,
                                 getAccessRules(libraryFile.getFilters()),
                                 getClasspathAttributes(libraryFile, artifact),
                                 true /* isExported */);
    return newLibraryEntry;

  }

  private static int getTotalwork(IClasspathEntry[] rawClasspath) {
    int sum = 0;
    for (IClasspathEntry element : rawClasspath) {
      if (isLibraryClasspathEntry(element.getPath())) {
        ++sum;
      }
    }
    return sum;
  }

  private static boolean isLibraryClasspathEntry(IPath path) {
    return path != null
        && path.segmentCount() == 2
        && Library.CONTAINER_PATH_PREFIX.equals(path.segment(0));
  }

  private static String getLibraryDescription(Library library) {
    if (!Strings.isNullOrEmpty(library.getName())) {
      return library.getName();
    } else {
      return library.getId();
    }
  }

  @Activate
  protected void initialize() {
    libraryFactory = new LibraryFactory();
    serializer = new LibraryClasspathContainerSerializer();
    IConfigurationElement[] configurationElements =
        extensionRegistry.getConfigurationElementsFor(LIBRARIES_EXTENSION_POINT);
    libraries = new HashMap<>(configurationElements.length);
    for (IConfigurationElement configurationElement : configurationElements) {
      try {
        Library library = libraryFactory.create(configurationElement);
        libraries.put(library.getId(), library);
      } catch (LibraryFactoryException exception) {
        logger.log(Level.SEVERE, "Failed to initialize libraries", exception); //$NON-NLS-1$
      }
    }
  }

  private static IAccessRule[] getAccessRules(List<Filter> filters) {
    IAccessRule[] accessRules = new IAccessRule[filters.size()];
    int idx = 0;
    for (Filter filter : filters) {
      int accessRuleKind =
          filter.isExclude() ? IAccessRule.K_NON_ACCESSIBLE : IAccessRule.K_ACCESSIBLE;
      accessRules[idx++] = JavaCore.newAccessRule(new Path(filter.getPattern()), accessRuleKind);
    }
    return accessRules;
  }

  private static IClasspathAttribute[] getClasspathAttributes(LibraryFile libraryFile,
                                                              Artifact artifact)
                                                                  throws CoreException {
    List<IClasspathAttribute> attributes =
        MavenCoordinatesHelper.createClasspathAttributes(libraryFile.getMavenCoordinates(),
                                                         artifact.getVersion());
    if (libraryFile.isExport()) {
      attributes.add(UpdateClasspathAttributeUtil.createDependencyAttribute(true /* isWebApp */));
    } else {
      attributes.add(UpdateClasspathAttributeUtil.createNonDependencyAttribute());
    }
    if (libraryFile.getSourceUri() != null) {
      addUriAttribute(attributes, CLASSPATH_ATTRIBUTE_SOURCE_URL, libraryFile.getSourceUri());
    }
    if (libraryFile.getJavadocUri() != null) {
      addUriAttribute(attributes, IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME,
                      libraryFile.getJavadocUri());
    }
    return attributes.toArray(new IClasspathAttribute[0]);
  }

  private static void addUriAttribute(List<IClasspathAttribute> attributes,
                                      String attributeName,
                                      URI uri) {
    try {
      attributes.add(JavaCore.newClasspathAttribute(attributeName, uri.toURL().toString()));
    } catch (MalformedURLException | IllegalArgumentException ex) {
      // disregard invalid URL
    }
  }
  
  @Reference
  public void setRepositoryService(ILibraryRepositoryService repositoryService) {
    this.repositoryService = repositoryService;
  }

  public void unsetRepositoryService(ILibraryRepositoryService repositoryService) {
    if (this.repositoryService == repositoryService) {
      this.repositoryService = null;
    }
  }

  @Reference
  public void setExtensionRegistry(IExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  public void unsetExtensionRegistry(IExtensionRegistry extensionRegistry) {
    if (this.extensionRegistry == extensionRegistry) {
      this.extensionRegistry = null;
    }
  }
}