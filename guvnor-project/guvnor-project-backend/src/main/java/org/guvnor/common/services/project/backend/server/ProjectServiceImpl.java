/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.guvnor.common.services.project.backend.server;

import java.util.*;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.guvnor.common.services.backend.exceptions.ExceptionUtilities;
import org.guvnor.common.services.project.backend.server.utils.IdentifierUtils;
import org.guvnor.common.services.backend.file.LinkedMetaInfFolderFilter;
import org.guvnor.common.services.project.events.NewPackageEvent;
import org.guvnor.common.services.project.events.NewProjectEvent;
import org.guvnor.common.services.project.model.POM;
import org.guvnor.common.services.project.model.Package;
import org.guvnor.common.services.project.model.Project;
import org.guvnor.common.services.project.model.ProjectImports;
import org.guvnor.common.services.project.service.KModuleService;
import org.guvnor.common.services.project.service.POMService;
import org.guvnor.common.services.project.service.PackageAlreadyExistsException;
import org.guvnor.common.services.project.service.ProjectService;
import org.guvnor.common.services.shared.metadata.MetadataService;
import org.guvnor.common.services.shared.metadata.model.Metadata;
import org.guvnor.common.services.workingset.client.model.WorkingSetSettings;
import org.jboss.errai.bus.server.annotations.Service;
import org.kie.commons.io.IOService;
import org.kie.commons.java.nio.base.options.CommentedOption;
import org.kie.commons.java.nio.file.DirectoryStream;
import org.kie.commons.java.nio.file.Files;
import org.uberfire.backend.repositories.Repository;
import org.uberfire.backend.server.config.ConfigGroup;
import org.uberfire.backend.server.config.ConfigItem;
import org.uberfire.backend.server.config.ConfigType;
import org.uberfire.backend.server.config.ConfigurationFactory;
import org.uberfire.backend.server.config.ConfigurationService;
import org.uberfire.backend.server.util.Paths;
import org.uberfire.backend.vfs.Path;
import org.uberfire.security.Identity;

@Service
@ApplicationScoped
public class ProjectServiceImpl
        implements ProjectService {

    private static final String SOURCE_FILENAME = "src";

    private static final String POM_PATH = "pom.xml";
    private static final String PROJECT_IMPORTS_PATH = "project.imports";
    private static final String KMODULE_PATH = "src/main/resources/META-INF/kmodule.xml";

    private static final String MAIN_SRC_PATH = "src/main/java";
    private static final String TEST_SRC_PATH = "src/test/java";
    private static final String MAIN_RESOURCES_PATH = "src/main/resources";
    private static final String TEST_RESOURCES_PATH = "src/test/resources";

    private static String[] sourcePaths = { MAIN_SRC_PATH, MAIN_RESOURCES_PATH, TEST_SRC_PATH, TEST_RESOURCES_PATH };

    private IOService ioService;
    private Paths paths;

    private POMService pomService;
    private KModuleService kModuleService;
    private MetadataService metadataService;
    private ProjectConfigurationContentHandler projectConfigurationContentHandler;

    private ConfigurationService configurationService;
    private ConfigurationFactory configurationFactory;

    private Event<NewProjectEvent> newProjectEvent;
    private Event<NewPackageEvent> newPackageEvent;

    private Identity identity;

    public ProjectServiceImpl() {
        // Boilerplate sacrifice for Weld
    }

    @Inject
    public ProjectServiceImpl( final @Named("ioStrategy") IOService ioService,
                               final Paths paths,
                               final POMService pomService,
                               final KModuleService kModuleService,
                               final MetadataService metadataService,
                               final ProjectConfigurationContentHandler projectConfigurationContentHandler,
                               final ConfigurationService configurationService,
                               final ConfigurationFactory configurationFactory,
                               final Event<NewProjectEvent> newProjectEvent,
                               final Event<NewPackageEvent> newPackageEvent,
                               final Identity identity ) {
        this.ioService = ioService;
        this.paths = paths;
        this.pomService = pomService;
        this.kModuleService = kModuleService;
        this.metadataService = metadataService;
        this.projectConfigurationContentHandler = projectConfigurationContentHandler;
        this.configurationService = configurationService;
        this.configurationFactory = configurationFactory;
        this.newProjectEvent = newProjectEvent;
        this.newPackageEvent = newPackageEvent;
        this.identity = identity;
    }

    @Override
    public WorkingSetSettings loadWorkingSetConfig( final Path project ) {
        //TODO {porcelli}
        return new WorkingSetSettings();
    }

    @Override
    public Project resolveProject( final Path resource ) {
        try {
            //Null resource paths cannot resolve to a Project
            if ( resource == null ) {
                return null;
            }

            //Check if resource is the project root
            org.kie.commons.java.nio.file.Path path = paths.convert( resource ).normalize();

            //A project root is the folder containing the pom.xml file. This will be the parent of the "src" folder
            if ( Files.isRegularFile( path ) ) {
                path = path.getParent();
            }
            if ( hasPom( path ) && hasKModule( path ) ) {
                return makeProject( path );
            }
            while ( path.getNameCount() > 0 && !path.getFileName().toString().equals( SOURCE_FILENAME ) ) {
                path = path.getParent();
            }
            if ( path.getNameCount() == 0 ) {
                return null;
            }
            path = path.getParent();
            if ( path.getNameCount() == 0 || path == null ) {
                return null;
            }
            if ( !hasPom( path ) ) {
                return null;
            }
            if ( !hasKModule( path ) ) {
                return null;
            }
            return makeProject( path );

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    private Project makeProject( final org.kie.commons.java.nio.file.Path nioProjectRootPath ) {
        final Path projectRootPath = paths.convert( nioProjectRootPath );
        final String projectName = projectRootPath.getFileName();
        final Path pomXMLPath = paths.convert( nioProjectRootPath.resolve( POM_PATH ),
                                               false );
        final Path kmoduleXMLPath = paths.convert( nioProjectRootPath.resolve( KMODULE_PATH ),
                                                   false );
        final Path importsXMLPath = paths.convert( nioProjectRootPath.resolve( PROJECT_IMPORTS_PATH ),
                                                   false );
        final Project project = new Project( projectRootPath,
                                             pomXMLPath,
                                             kmoduleXMLPath,
                                             importsXMLPath,
                                             projectName );

        //Copy in Security Roles required to access this resource
        final ConfigGroup projectConfiguration = findProjectConfig( projectRootPath );
        if ( projectConfiguration != null ) {
            ConfigItem<List<String>> roles = projectConfiguration.getConfigItem( "security:roles" );
            if ( roles != null ) {
                for ( String role : roles.getValue() ) {
                    project.getRoles().add( role );
                }
            }
        }
        return project;
    }

    @Override
    public Package resolvePackage( final Path resource ) {
        try {
            //Null resource paths cannot resolve to a Project
            if ( resource == null ) {
                return null;
            }

            //If Path is not within a Project we cannot resolve a package
            final Project project = resolveProject( resource );
            if ( project == null ) {
                return null;
            }

            //pom.xml and kmodule.xml are not inside packages
            if ( isPom( resource ) || isKModule( resource ) ) {
                return null;
            }

            return makePackage( project,
                                resource );

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    @Override
    public Set<Package> resolvePackages( final Project project ) {
        final Set<Package> packages = new HashSet<Package>();
        final Set<String> packageNames = new HashSet<String>();
        if ( project == null ) {
            return packages;
        }
        //Build a set of all package names across /src/main/java, /src/main/resources, /src/test/java and /src/test/resources paths
        //It is possible (if the project was not created within the workbench that some packages only exist in certain paths)
        final Path projectRoot = project.getRootPath();
        final org.kie.commons.java.nio.file.Path nioProjectRootPath = paths.convert( projectRoot );
        for ( String src : sourcePaths ) {
            final org.kie.commons.java.nio.file.Path nioPackageRootSrcPath = nioProjectRootPath.resolve( src );
            packageNames.addAll( getPackageNames( nioProjectRootPath,
                    nioPackageRootSrcPath ) );
        }

        //Construct Package objects for each package name
        final java.util.Set<String> resolvedPackages = new java.util.HashSet<String>();
        for ( String packagePathSuffix : packageNames ) {
            for ( String src : sourcePaths ) {
                final org.kie.commons.java.nio.file.Path nioPackagePath = nioProjectRootPath.resolve( src ).resolve( packagePathSuffix );
                if ( Files.exists( nioPackagePath ) && !resolvedPackages.contains( packagePathSuffix ) ) {
                    packages.add( resolvePackage( paths.convert(nioPackagePath, false) ) );
                    resolvedPackages.add( packagePathSuffix );
                }
            }
        }

        return packages;
    }

    private Set<String> getPackageNames( final org.kie.commons.java.nio.file.Path nioProjectRootPath,
                                         final org.kie.commons.java.nio.file.Path nioPackageSrcPath ) {
        final Set<String> packageNames = new HashSet<String>();
        if ( !Files.exists( nioPackageSrcPath ) ) {
            return packageNames;
        }
        packageNames.add( getPackagePathSuffix( nioProjectRootPath,
                nioPackageSrcPath ) );
        final LinkedMetaInfFolderFilter metaDataFileFilter = new LinkedMetaInfFolderFilter();
        final DirectoryStream<org.kie.commons.java.nio.file.Path> nioChildPackageSrcPaths = ioService.newDirectoryStream( nioPackageSrcPath,
                metaDataFileFilter );
        for ( org.kie.commons.java.nio.file.Path nioChildPackageSrcPath : nioChildPackageSrcPaths ) {
            if ( Files.isDirectory( nioChildPackageSrcPath ) ) {
                packageNames.addAll( getPackageNames( nioProjectRootPath,
                        nioChildPackageSrcPath ) );
            }
        }
        return packageNames;
    }

    private String getPackagePathSuffix( final org.kie.commons.java.nio.file.Path nioProjectRootPath,
                                         final org.kie.commons.java.nio.file.Path nioPackagePath ) {
        final org.kie.commons.java.nio.file.Path nioMainSrcPath = nioProjectRootPath.resolve( MAIN_SRC_PATH );
        final org.kie.commons.java.nio.file.Path nioTestSrcPath = nioProjectRootPath.resolve( TEST_SRC_PATH );
        final org.kie.commons.java.nio.file.Path nioMainResourcesPath = nioProjectRootPath.resolve( MAIN_RESOURCES_PATH );
        final org.kie.commons.java.nio.file.Path nioTestResourcesPath = nioProjectRootPath.resolve( TEST_RESOURCES_PATH );

        String packageName = null;
        org.kie.commons.java.nio.file.Path packagePath = null;
        if ( nioPackagePath.startsWith( nioMainSrcPath ) ) {
            packagePath = nioMainSrcPath.relativize( nioPackagePath );
            packageName = packagePath.toString();
        } else if ( nioPackagePath.startsWith( nioTestSrcPath ) ) {
            packagePath = nioTestSrcPath.relativize( nioPackagePath );
            packageName = packagePath.toString();
        } else if ( nioPackagePath.startsWith( nioMainResourcesPath ) ) {
            packagePath = nioMainResourcesPath.relativize( nioPackagePath );
            packageName = packagePath.toString();
        } else if ( nioPackagePath.startsWith( nioTestResourcesPath ) ) {
            packagePath = nioTestResourcesPath.relativize( nioPackagePath );
            packageName = packagePath.toString();
        }

        return packageName;
    }

    private Package makePackage( final Project project,
                                 final Path resource ) {
        final Path projectRoot = project.getRootPath();
        final org.kie.commons.java.nio.file.Path nioProjectRoot = paths.convert( projectRoot );
        final org.kie.commons.java.nio.file.Path nioMainSrcPath = nioProjectRoot.resolve( MAIN_SRC_PATH );
        final org.kie.commons.java.nio.file.Path nioTestSrcPath = nioProjectRoot.resolve( TEST_SRC_PATH );
        final org.kie.commons.java.nio.file.Path nioMainResourcesPath = nioProjectRoot.resolve( MAIN_RESOURCES_PATH );
        final org.kie.commons.java.nio.file.Path nioTestResourcesPath = nioProjectRoot.resolve( TEST_RESOURCES_PATH );

        org.kie.commons.java.nio.file.Path nioResource = paths.convert( resource );

        if ( Files.isRegularFile( nioResource ) ) {
            nioResource = nioResource.getParent();
        }

        String packageName = null;
        org.kie.commons.java.nio.file.Path packagePath = null;
        if ( nioResource.startsWith( nioMainSrcPath ) ) {
            packagePath = nioMainSrcPath.relativize( nioResource );
            packageName = packagePath.toString().replaceAll( "/",
                                                             "." );
        } else if ( nioResource.startsWith( nioTestSrcPath ) ) {
            packagePath = nioTestSrcPath.relativize( nioResource );
            packageName = packagePath.toString().replaceAll( "/",
                                                             "." );
        } else if ( nioResource.startsWith( nioMainResourcesPath ) ) {
            packagePath = nioMainResourcesPath.relativize( nioResource );
            packageName = packagePath.toString().replaceAll( "/",
                                                             "." );
        } else if ( nioResource.startsWith( nioTestResourcesPath ) ) {
            packagePath = nioTestResourcesPath.relativize( nioResource );
            packageName = packagePath.toString().replaceAll( "/",
                                                             "." );
        }

        //Resource was not inside a package
        if ( packageName == null ) {
            return null;
        }

        boolean includeAttributes = Files.exists( nioMainSrcPath.resolve( packagePath ) );
        final Path mainSrcPath = paths.convert( nioMainSrcPath.resolve( packagePath ),
                                                includeAttributes );
        includeAttributes = Files.exists( nioTestSrcPath.resolve( packagePath ) );
        final Path testSrcPath = paths.convert( nioTestSrcPath.resolve( packagePath ),
                                                includeAttributes );
        includeAttributes = Files.exists( nioMainResourcesPath.resolve( packagePath ) );
        final Path mainResourcesPath = paths.convert( nioMainResourcesPath.resolve( packagePath ),
                                                      includeAttributes );
        includeAttributes = Files.exists( nioTestResourcesPath.resolve( packagePath ) );
        final Path testResourcesPath = paths.convert( nioTestResourcesPath.resolve( packagePath ),
                                                      includeAttributes );

        final Package pkg = new Package( project.getRootPath(),
                                         mainSrcPath,
                                         testSrcPath,
                                         mainResourcesPath,
                                         testResourcesPath,
                                         packageName,
                                         getPackageDisplayName( packageName ) );
        return pkg;
    }

    private String getPackageDisplayName( final String packageName ) {
        return packageName.isEmpty() ? "<default>" : packageName;
    }

    @Override
    public boolean isPom( final Path resource ) {
        try {
            //Null resource paths cannot resolve to a Project
            if ( resource == null ) {
                return false;
            }

            //Check if path equals pom.xml
            final Project project = resolveProject( resource );
            final org.kie.commons.java.nio.file.Path path = paths.convert( resource ).normalize();
            final org.kie.commons.java.nio.file.Path pomFilePath = paths.convert( project.getPomXMLPath() );
            return path.startsWith( pomFilePath );

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    @Override
    public boolean isKModule( final Path resource ) {
        try {
            //Null resource paths cannot resolve to a Project
            if ( resource == null ) {
                return false;
            }

            //Check if path equals kmodule.xml
            final Project project = resolveProject( resource );
            final org.kie.commons.java.nio.file.Path path = paths.convert( resource ).normalize();
            final org.kie.commons.java.nio.file.Path kmoduleFilePath = paths.convert( project.getKModuleXMLPath() );
            return path.startsWith( kmoduleFilePath );

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    @Override
    public Project newProject( final Repository repository,
                               final String projectName,
                               final POM pom,
                               final String baseUrl ) {
        try {
            //Projects are always created in the FS root
            final Path fsRoot = repository.getRoot();
            final Path projectRootPath = paths.convert( paths.convert( fsRoot ).resolve( projectName ),
                                                        false );

            //Set-up project structure and KModule.xml
            kModuleService.setUpKModuleStructure( projectRootPath );

            //Create POM.xml
            pomService.create( projectRootPath,
                               baseUrl,
                               pom );

            //Create Project configuration
            final Path projectConfigPath = paths.convert( paths.convert( projectRootPath ).resolve( PROJECT_IMPORTS_PATH ),
                                                          false );
            ioService.createFile( paths.convert( projectConfigPath ) );
            ioService.write( paths.convert( projectConfigPath ),
                             projectConfigurationContentHandler.toString( new ProjectImports() ) );

            //Raise an event for the new project
            final Project project = resolveProject( projectRootPath );
            newProjectEvent.fire( new NewProjectEvent( project ) );

            //Create a default workspace based on the GAV
            final String legalJavaGroupId[] = IdentifierUtils.convertMavenIdentifierToJavaIdentifier( pom.getGav().getGroupId().split( "\\.",
                                                                                                                                       -1 ) );
            final String legalJavaArtifactId[] = IdentifierUtils.convertMavenIdentifierToJavaIdentifier( pom.getGav().getArtifactId().split( "\\.",
                                                                                                                                             -1 ) );
            final String defaultWorkspacePath = StringUtils.join( legalJavaGroupId,
                                                                  "/" ) + "/" + StringUtils.join( legalJavaArtifactId,
                                                                                                  "/" );
            final Path defaultPackagePath = paths.convert( paths.convert( projectRootPath ).resolve( MAIN_RESOURCES_PATH ),
                                                           false );
            final Package defaultPackage = resolvePackage( defaultPackagePath );
            final Package defaultWorkspacePackage = doNewPackage( defaultPackage,
                                                                  defaultWorkspacePath );

            //Raise an event for the new project's default workspace
            newPackageEvent.fire( new NewPackageEvent( defaultWorkspacePackage ) );

            //Return new project
            return project;

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    @Override
    public Package newPackage( final Package parentPackage,
                               final String packageName ) {
        try {
            //Make new Package
            final Package newPackage = doNewPackage( parentPackage,
                                                     packageName );

            //Raise an event for the new package
            newPackageEvent.fire( new NewPackageEvent( newPackage ) );

            //Return the new package
            return newPackage;

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    private Package doNewPackage( final Package parentPackage,
                                  final String packageName ) {
        //If the package name contains separators, create sub-folders
        String newPackageName = packageName.toLowerCase();
        if ( newPackageName.contains( "." ) ) {
            newPackageName = newPackageName.replace( ".",
                                                     "/" );
        }

        //Return new package
        final Path mainSrcPath = parentPackage.getPackageMainSrcPath();
        final Path testSrcPath = parentPackage.getPackageTestSrcPath();
        final Path mainResourcesPath = parentPackage.getPackageMainResourcesPath();
        final Path testResourcesPath = parentPackage.getPackageTestResourcesPath();

        Path pkgPath = null;

        final org.kie.commons.java.nio.file.Path nioMainSrcPackagePath = paths.convert( mainSrcPath ).resolve( newPackageName );
        if ( !Files.exists( nioMainSrcPackagePath ) ) {
            pkgPath = paths.convert( ioService.createDirectory( nioMainSrcPackagePath ) );
        }
        final org.kie.commons.java.nio.file.Path nioTestSrcPackagePath = paths.convert( testSrcPath ).resolve( newPackageName );
        if ( !Files.exists( nioTestSrcPackagePath ) ) {
            pkgPath = paths.convert( ioService.createDirectory( nioTestSrcPackagePath ) );
        }
        final org.kie.commons.java.nio.file.Path nioMainResourcesPackagePath = paths.convert( mainResourcesPath ).resolve( newPackageName );
        if ( !Files.exists( nioMainResourcesPackagePath ) ) {
            pkgPath = paths.convert( ioService.createDirectory( nioMainResourcesPackagePath ) );
        }
        final org.kie.commons.java.nio.file.Path nioTestResourcesPackagePath = paths.convert( testResourcesPath ).resolve( newPackageName );
        if ( !Files.exists( nioTestResourcesPackagePath ) ) {
            pkgPath = paths.convert( ioService.createDirectory( nioTestResourcesPackagePath ) );
        }

        //If pkgPath is null the package already existed in src/main/java, scr/main/resources, src/test/java and src/test/resources
        if ( pkgPath == null ) {
            throw new PackageAlreadyExistsException( packageName );
        }

        //Return new package
        final Package newPackage = resolvePackage( pkgPath );
        return newPackage;
    }

    private boolean hasPom( final org.kie.commons.java.nio.file.Path path ) {
        final org.kie.commons.java.nio.file.Path pomPath = path.resolve( POM_PATH );
        return Files.exists( pomPath );
    }

    private boolean hasKModule( final org.kie.commons.java.nio.file.Path path ) {
        final org.kie.commons.java.nio.file.Path kmodulePath = path.resolve( KMODULE_PATH );
        return Files.exists( kmodulePath );
    }

    @Override
    public ProjectImports load( final Path path ) {
        final String content = ioService.readAllString( paths.convert( path ) );
        return projectConfigurationContentHandler.toModel( content );
    }

    @Override
    public Path save( final Path resource,
                      final ProjectImports projectImports,
                      final Metadata metadata,
                      final String comment ) {
        try {
            ioService.write( paths.convert( resource ),
                             projectConfigurationContentHandler.toString( projectImports ),
                             metadataService.setUpAttributes( resource,
                                                              metadata ),
                             makeCommentedOption( comment ) );

            //The pom.xml, kmodule.xml and project.imports are all saved from ProjectScreenPresenter
            //We only raise InvalidateDMOProjectCacheEvent and ResourceUpdatedEvent(pom.xml) events once
            //in POMService.save to avoid duplicating events (and re-construction of DMO).

            return resource;

        } catch ( Exception e ) {
            throw ExceptionUtilities.handleException( e );
        }
    }

    private CommentedOption makeCommentedOption( final String commitMessage ) {
        final String name = identity.getName();
        final Date when = new Date();
        final CommentedOption co = new CommentedOption( name,
                                                        null,
                                                        commitMessage,
                                                        when );
        return co;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void addRole( final Project project,
                         final String role ) {
        ConfigGroup thisProjectConfig = findProjectConfig( project.getRootPath() );

        if ( thisProjectConfig == null ) {
            thisProjectConfig = configurationFactory.newConfigGroup( ConfigType.PROJECT,
                                                                     project.getRootPath().toURI(),
                                                                     "Project '" + project.getProjectName() + "' configuration" );
            thisProjectConfig.addConfigItem( configurationFactory.newConfigItem( "security:roles",
                                                                                 new ArrayList<String>() ) );
            configurationService.addConfiguration( thisProjectConfig );
        }

        if ( thisProjectConfig != null ) {
            final ConfigItem<List> roles = thisProjectConfig.getConfigItem( "security:roles" );
            roles.getValue().add( role );

            configurationService.updateConfiguration( thisProjectConfig );

        } else {
            throw new IllegalArgumentException( "Project " + project.getProjectName() + " not found" );
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void removeRole( final Project project,
                            final String role ) {
        final ConfigGroup thisProjectConfig = findProjectConfig( project.getRootPath() );

        if ( thisProjectConfig != null ) {
            final ConfigItem<List> roles = thisProjectConfig.getConfigItem( "security:roles" );
            roles.getValue().remove( role );

            configurationService.updateConfiguration( thisProjectConfig );

        } else {
            throw new IllegalArgumentException( "Project " + project.getProjectName() + " not found" );
        }
    }

    protected ConfigGroup findProjectConfig( final Path projectRoot ) {
        final Collection<ConfigGroup> groups = configurationService.getConfiguration( ConfigType.PROJECT );
        if ( groups != null ) {
            for ( ConfigGroup groupConfig : groups ) {
                if ( groupConfig.getName().equals( projectRoot.toURI() ) ) {
                    return groupConfig;
                }
            }
        }
        return null;
    }

}