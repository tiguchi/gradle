/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.nativeplatform.tasks;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Incubating;
import org.gradle.api.Transformer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.util.GFileUtils;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;

/**
 * Installs an executable with it's dependent libraries so it can be easily executed.
 */
@Incubating
public class InstallExecutable extends DefaultTask {
    private final Property<NativePlatform> targetPlatform;
    private final Property<NativeToolChain> toolChain;
    private final DirectoryProperty installDirectory;
    private final RegularFileProperty executable;
    private final RegularFileProperty installedExecutable;
    private final ConfigurableFileCollection libs;
    private final WorkerLeaseService workerLeaseService;

    /**
     * Injects a {@link WorkerLeaseService} instance.
     *
     * @since 4.2
     */
    @Inject
    public InstallExecutable(WorkerLeaseService workerLeaseService) {
        ObjectFactory objectFactory = getProject().getObjects();
        this.workerLeaseService = workerLeaseService;
        this.libs = getProject().files();
        this.installDirectory = objectFactory.directoryProperty();
        this.installedExecutable = objectFactory.fileProperty();
        this.installedExecutable.set(getLibDirectory().map(new Transformer<RegularFile, Directory>() {
            @Override
            public RegularFile transform(Directory directory) {
                return directory.file(executable.getAsFile().get().getName());
            }
        }));
        this.executable = objectFactory.fileProperty();
        // A further work around for missing ability to skip task when input file is missing (see #getInputFileIfExists below)
        dependsOn(executable);
        this.targetPlatform = objectFactory.property(NativePlatform.class);
        this.toolChain = objectFactory.property(NativeToolChain.class);
    }

    /**
     * The tool chain used for linking.
     *
     * @since 4.7
     */
    @Internal
    public Property<NativeToolChain> getToolChain() {
        return toolChain;
    }

    /**
     * The platform being linked for.
     *
     * @since 4.7
     */
    @Nested
    public Property<NativePlatform> getTargetPlatform() {
        return targetPlatform;
    }

    /**
     * The directory to install files into.
     *
     * @since 4.1
     */
    @OutputDirectory
    public DirectoryProperty getInstallDirectory() {
        return installDirectory;
    }

    /**
     * The executable file to install.
     *
     * @since 4.7
     *
     */
    @Internal("Covered by inputFileIfExists")
    public RegularFileProperty getExecutableFile() {
        return executable;
    }

    /**
     * The location of the installed executable file.
     *
     * @since 4.7
     */
    @OutputFile
    public RegularFileProperty getInstalledExecutable() {
        return installedExecutable;
    }

    /**
     * Workaround for when the task is given an input file that doesn't exist
     *
     * @since 4.3
     */
    // TODO - allow @InputFile and @SkipWhenEmpty to be attached to getExecutableFile()
    @SkipWhenEmpty
    @Nullable
    @Optional
    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputFile
    protected File getInputFileIfExists() {
        RegularFileProperty sourceFile = getExecutableFile();
        if (sourceFile.isPresent() && sourceFile.get().getAsFile().exists()) {
            return sourceFile.get().getAsFile();
        } else {
            return null;
        }
    }

    /**
     * The library files that should be installed.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public FileCollection getLibs() {
        return libs;
    }

    public void setLibs(FileCollection libs) {
        this.libs.setFrom(libs);
    }

    /**
     * Adds a set of library files to be installed. The provided libs object is evaluated as per {@link org.gradle.api.Project#files(Object...)}.
     */
    public void lib(Object libs) {
        this.libs.from(libs);
    }

    /**
     * Returns the script file that can be used to run the install image.
     *
     * @since 4.4
     */
    @Internal("covered by getInstallDirectory")
    public Provider<RegularFile> getRunScriptFile() {
        return installDirectory.file(executable.map(new Transformer<CharSequence, RegularFile>() {
            @Override
            public CharSequence transform(RegularFile regularFile) {
                OperatingSystem operatingSystem = OperatingSystem.forName(targetPlatform.get().getOperatingSystem().getName());
                return operatingSystem.getScriptName(regularFile.getAsFile().getName());
            }
        }));
    }

    @Inject
    protected FileSystem getFileSystem() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected FileOperations getFileOperations() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    public void install() {
        // TODO: Migrate this to the worker API once the FileSystem and FileOperations services can be injected
        workerLeaseService.withoutProjectLock(new Runnable() {
            @Override
            public void run() {
                if (targetPlatform.get().getOperatingSystem().isWindows()) {
                    installWindows();
                } else {
                    installUnix();
                }
            }
        });
    }

    private Provider<Directory> getLibDirectory() {
        return getInstallDirectory().dir("lib");
    }

    private void installWindows() {
        final File executable = getExecutableFile().get().getAsFile();

        installToDir(getLibDirectory().get().getAsFile());

        StringBuilder toolChainPath = new StringBuilder();

        NativeToolChain toolChain = getToolChain().get();
        if (toolChain instanceof Gcc) {
            // Gcc on windows requires the path to be set
            toolChainPath.append("SET PATH=");
            for (File pathEntry : ((Gcc) toolChain).getPath()) {
                toolChainPath.append(pathEntry.getAbsolutePath()).append(";");
            }

            toolChainPath.append("%PATH%");
        }

        String runScriptText =
              "\n@echo off"
            + "\nSETLOCAL"
            + "\n" + toolChainPath
            + "\nCALL \"%~dp0lib\\" + executable.getName() + "\" %*"
            + "\nEXIT /B %ERRORLEVEL%"
            + "\nENDLOCAL"
            + "\n";
        GFileUtils.writeFile(runScriptText, getRunScriptFile().get().getAsFile());
    }

    private void installUnix() {
        final File destination = getInstallDirectory().get().getAsFile();
        final File executable = getExecutableFile().get().getAsFile();

        installToDir(new File(destination, "lib"));

        String runScriptText =
              "#!/bin/sh"
            + "\nAPP_BASE_NAME=`dirname \"$0\"`"
            + "\nDYLD_LIBRARY_PATH=\"$APP_BASE_NAME/lib\""
            + "\nexport DYLD_LIBRARY_PATH"
            + "\nLD_LIBRARY_PATH=\"$APP_BASE_NAME/lib\""
            + "\nexport LD_LIBRARY_PATH"
            + "\nexec \"$APP_BASE_NAME/lib/" + executable.getName() + "\" \"$@\""
            + "\n";
        File runScript = getRunScriptFile().get().getAsFile();
        GFileUtils.writeFile(runScriptText, runScript);

        getFileSystem().chmod(runScript, 0755);
    }

    private void installToDir(final File binaryDir) {
        getFileOperations().sync(new Action<CopySpec>() {
            public void execute(CopySpec copySpec) {
                copySpec.into(binaryDir);
                copySpec.from(getExecutableFile());
                copySpec.from(getLibs());
            }

        });
    }
}
