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

import org.apache.ignite.internal.schema.BinaryTupleSchema.Element;
import org.apache.ignite.internal.tostring.IgniteToStringExclude;
import org.apache.ignite.internal.tostring.S;
import org.apache.ignite.internal.type.NativeType;

/**
 * Virtual column implementation.
 */
public class VirtualColumn {
    private final int columnIndex;
    private final Element type;
    private final boolean nullable;
    @IgniteToStringExclude
    private final Object value;

    public VirtualColumn(int columnIndex, NativeType type, boolean nullable, Object value) {
        this.columnIndex = columnIndex;
        this.value = value;
        this.type = new Element(type, nullable);
        this.nullable = nullable;
    }

    public int columnIndex() {
        return columnIndex;
    }

    public Element schemaType() {
        return type;
    }

    public boolean isNullable() {
        return nullable;
    }

    public <T> T value() {
        return (T) value;
    }

    @Override
    public String toString() {
        return S.toString(VirtualColumn.class, this);
    }
}
