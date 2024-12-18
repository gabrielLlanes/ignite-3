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

package org.apache.ignite.internal.sql.engine.exec;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.sql.engine.InternalSqlRow;
import org.apache.ignite.internal.sql.engine.SqlOperationContext;
import org.apache.ignite.internal.sql.engine.prepare.QueryPlan;

/**
 * SQL query plan execution interface.
 */
public interface ExecutionService extends LifecycleAware {
    /**
     * Executes the given plan.
     *
     * @param plan Plan to execute.
     * @param operationContext Context of operation.
     * @return Future that will be completed when cursor is successfully initialized, implying for distributed plans all fragments have been
     *         sent successfully.
     */
    CompletableFuture<AsyncDataCursorExt<InternalSqlRow>> executePlan(
            QueryPlan plan, SqlOperationContext operationContext
    );
}
