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

package org.apache.ignite.internal.sql.engine.util;

import org.apache.ignite.internal.binarytuple.BinaryTupleBuilder;
import org.apache.ignite.internal.binarytuple.BinaryTupleParser.Sink;
import org.apache.ignite.internal.lang.InternalTuple;
import org.apache.ignite.internal.schema.BinaryTuple;
import org.apache.ignite.internal.schema.InternalTupleEx;

/**
 * A projected tuple wrapper that is best effort to avoiding unnecessary (de-)serialization during tuple normalization.
 *
 * <p>It's up to the caller to get sure that provided tuple respect the format.
 *
 * <p>Not thread safe!
 *
 * @see AbstractProjectedTuple
 */
public class ProjectedTuple extends AbstractProjectedTuple {
    /**
     * Constructor.
     *
     * @param delegate An original tuple to create projection from.
     * @param projection A projection. That is, desired order of fields in original tuple. In that projection, index of the array is
     *         an index of field in resulting projection, and an element of the array at that index is an index of column in original
     *         tuple.
     */
    public ProjectedTuple(InternalTuple delegate, int[] projection) {
        super((InternalTupleEx) delegate, projection);
    }

    @Override
    protected void normalize() {
        BinaryTupleBuilder builder;
        if (delegate instanceof BinaryTuple) {
            // Estimate total data size.
            var stats = new Sink() {
                int estimatedValueSize = 0;

                @Override
                public void nextElement(int index, int begin, int end) {
                    estimatedValueSize += end - begin;
                }
            };

            for (int columnIndex : projection) {
                ((BinaryTuple) delegate).fetch(columnIndex, stats);
            }

            builder = new BinaryTupleBuilder(projection.length, stats.estimatedValueSize, true);
        } else {
            builder = new BinaryTupleBuilder(projection.length, 32, false);
        }

        // Extract projected columns into a builder.
        int[] newProjection = new int[projection.length];
        for (int i = 0; i < projection.length; i++) {
            copyValue(builder, i);

            newProjection[i] = i;
        }

        delegate = new BinaryTuple(projection.length, builder.build());
        projection = newProjection;
    }

    @Override
    public void copyValue(BinaryTupleBuilder builder, int columnIndex) {
        delegate.copyValue(builder, projection[columnIndex]);
    }
}
