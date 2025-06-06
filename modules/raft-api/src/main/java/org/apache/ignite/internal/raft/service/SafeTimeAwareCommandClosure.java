/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.raft.service;

import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.raft.WriteCommand;
import org.jetbrains.annotations.Nullable;

/**
 * The marker interface for a safe time aware command closure.
 */
public interface SafeTimeAwareCommandClosure extends CommandClosure<WriteCommand> {
    /**
     * Gets the safe timestamp.
     *
     * @return The timestamp.
     */
    @Override
    @Nullable HybridTimestamp safeTimestamp();

    /**
     * Sets the safe time.
     *
     * @param safeTs The timestamp.
     */
    default void safeTimestamp(HybridTimestamp safeTs) {}
}
