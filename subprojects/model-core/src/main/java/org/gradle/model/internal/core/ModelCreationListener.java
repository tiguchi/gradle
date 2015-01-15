/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.model.internal.core;

import org.gradle.api.Nullable;
import org.gradle.model.internal.type.ModelType;

public abstract class ModelCreationListener {
    /**
     * Returns the path of the node which this listener is interested in, or null if path is not relevant.
     */
    @Nullable
    public ModelPath matchPath() {
        return null;
    }

    /**
     * Returns the parent path of the node which this listener is interested in, or null if path is not relevant.
     */
    @Nullable
    public ModelPath matchParent() {
        return null;
    }

    public ModelPath matchScope() {
        return null;
    }

    /**
     * Returns the type of node which this listener is interested in, or null if type is not relevant.
     */
    @Nullable
    public ModelType<?> matchType() {
        return null;
    }

    /**
     * Invoked for each node that matches the criteria specified by {@link #matchPath()}, {@link #matchParent()} or {@link #matchType()}, or every node if
     * no criteria specified. Stops notifying listener with further node when this method returns true.
     */
    public abstract boolean onCreate(ModelNode node);
}
