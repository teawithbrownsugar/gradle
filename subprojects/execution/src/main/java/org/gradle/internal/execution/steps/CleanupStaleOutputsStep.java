/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.Context;
import org.gradle.internal.execution.OutputChangeListener;
import org.gradle.internal.execution.Result;
import org.gradle.internal.execution.Step;
import org.gradle.internal.execution.history.OutputFilesRepository;
import org.gradle.internal.execution.workspace.BuildOutputCleanupRegistry;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CleanupStaleOutputsStep<C extends Context, R extends Result> implements Step<C, R> {
    public static final String CLEAN_STALE_OUTPUTS_DISPLAY_NAME = "Clean stale outputs";

    private static final Logger LOGGER = LoggerFactory.getLogger(CleanupStaleOutputsStep.class);

    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildOutputCleanupRegistry cleanupRegistry;
    private final Deleter deleter;
    private final OutputChangeListener outputChangeListener;
    private final OutputFilesRepository outputFilesRepository;
    private final Step<? super C, ? extends R> delegate;

    public CleanupStaleOutputsStep(
        BuildOperationExecutor buildOperationExecutor,
        BuildOutputCleanupRegistry cleanupRegistry,
        Deleter deleter,
        OutputChangeListener outputChangeListener,
        OutputFilesRepository outputFilesRepository,
        Step<? super C, ? extends R> delegate
    ) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.cleanupRegistry = cleanupRegistry;
        this.deleter = deleter;
        this.outputChangeListener = outputChangeListener;
        this.outputFilesRepository = outputFilesRepository;
        this.delegate = delegate;
    }

    @Override
    public R execute(C context) {
        Set<File> filesToDelete = new HashSet<>();
        context.getWork().visitOutputProperties((propertyName, type, root) -> {
            if (cleanupRegistry.isOutputOwnedByBuild(root)
                && !outputFilesRepository.isGeneratedByGradle(root)
                && root.exists()
            ) {
                filesToDelete.add(root);
            }
        });
        if (!filesToDelete.isEmpty()) {
            outputChangeListener.beforeOutputChange(
                filesToDelete.stream()
                    .map(File::getAbsolutePath)
                    .collect(Collectors.toList())
            );
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) throws IOException {
                    for (File file : filesToDelete) {
                        if (file.exists()) {
                            LOGGER.info("Deleting stale output file: {}", file.getAbsolutePath());
                            deleter.deleteRecursively(file);
                        }
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor
                        .displayName(CLEAN_STALE_OUTPUTS_DISPLAY_NAME)
                        .progressDisplayName("Cleaning stale outputs");
                }
            });
        }
        return delegate.execute(context);
    }
}