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

package org.apache.ignite.internal.storage.rocksdb.instance;

import static org.apache.ignite.internal.storage.rocksdb.ColumnFamilyUtils.sortedIndexCfName;
import static org.apache.ignite.internal.storage.rocksdb.RocksDbStorageUtils.KEY_BYTE_ORDER;
import static org.apache.ignite.internal.util.ArrayUtils.BYTE_EMPTY_ARRAY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.apache.ignite.internal.configuration.testframework.ConfigurationExtension;
import org.apache.ignite.internal.configuration.testframework.InjectConfiguration;
import org.apache.ignite.internal.rocksdb.ColumnFamily;
import org.apache.ignite.internal.storage.index.StorageSortedIndexDescriptor.StorageSortedIndexColumnDescriptor;
import org.apache.ignite.internal.storage.rocksdb.RocksDbDataRegion;
import org.apache.ignite.internal.storage.rocksdb.RocksDbStorageEngine;
import org.apache.ignite.internal.storage.rocksdb.configuration.schema.RocksDbStorageEngineConfiguration;
import org.apache.ignite.internal.testframework.IgniteAbstractTest;
import org.apache.ignite.internal.type.NativeTypes;
import org.apache.ignite.internal.util.IgniteUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;

/** Contains tests for {@link SharedRocksDbInstance}. */
@ExtendWith(ConfigurationExtension.class)
class SharedRocksDbInstanceTest extends IgniteAbstractTest {
    private RocksDbStorageEngine engine;

    private RocksDbDataRegion dataRegion;

    private SharedRocksDbInstance rocksDb;

    @BeforeEach
    void setUp(@InjectConfiguration RocksDbStorageEngineConfiguration engineConfig) throws Exception {
        engine = new RocksDbStorageEngine("test", engineConfig, workDir);

        engine.start();

        dataRegion = new RocksDbDataRegion(engineConfig.defaultRegion());

        dataRegion.start();

        rocksDb = createDb();
    }

    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAllManually(
                rocksDb == null ? null : rocksDb::stop,
                dataRegion == null ? null : dataRegion::stop,
                engine == null ? null : engine::stop
        );
    }

    private SharedRocksDbInstance createDb() throws Exception {
        return new SharedRocksDbInstanceCreator().create(engine, dataRegion, workDir);
    }

    @Test
    void testSortedIndexCfCaching() {
        byte[] fooName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("a", NativeTypes.INT64, true, true)
        ));

        byte[] barName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("b", NativeTypes.UUID, true, true)
        ));

        byte[] bazName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("c", NativeTypes.INT64, true, true)
        ));

        byte[] quuxName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("d", NativeTypes.INT64, true, true)
        ));

        ColumnFamily foo = rocksDb.getOrCreateSortedIndexCf(fooName, 1, 0);
        // Different index CF, same table.
        ColumnFamily bar = rocksDb.getOrCreateSortedIndexCf(barName, 2, 0);
        // Same index CF, same table.
        ColumnFamily baz = rocksDb.getOrCreateSortedIndexCf(bazName, 3, 0);
        // Same index CF, different table.
        ColumnFamily quux = rocksDb.getOrCreateSortedIndexCf(quuxName, 4, 1);

        assertThat(foo, is(sameInstance(baz)));
        assertThat(foo, is(not(sameInstance(bar))));
        assertThat(quux, is((sameInstance(baz))));

        rocksDb.destroySortedIndexCfIfNeeded(fooName, 1);

        assertTrue(cfExists(fooName));

        rocksDb.destroySortedIndexCfIfNeeded(barName, 2);

        assertFalse(cfExists(barName));

        rocksDb.destroySortedIndexCfIfNeeded(bazName, 3);

        assertTrue(cfExists(fooName));

        rocksDb.destroySortedIndexCfIfNeeded(quuxName, 4);

        assertFalse(cfExists(fooName));
    }

    @Test
    void testSortedIndexRecovery() throws Exception {
        byte[] fooName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("a", NativeTypes.INT64, true, true)
        ));

        byte[] barName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("b", NativeTypes.UUID, true, true)
        ));

        byte[] bazName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("c", NativeTypes.INT64, true, true)
        ));

        byte[] quuxName = sortedIndexCfName(List.of(
                new StorageSortedIndexColumnDescriptor("d", NativeTypes.INT64, true, true)
        ));

        ColumnFamily foo = rocksDb.getOrCreateSortedIndexCf(fooName, 1, 0);
        // Different index CF, same table.
        ColumnFamily bar = rocksDb.getOrCreateSortedIndexCf(barName, 2, 0);
        // Same index CF, same table.
        ColumnFamily baz = rocksDb.getOrCreateSortedIndexCf(bazName, 3, 0);
        // Same index CF, different table.
        ColumnFamily quux = rocksDb.getOrCreateSortedIndexCf(quuxName, 4, 1);

        assertThat(rocksDb.sortedIndexes(0), is(Map.of(1, foo, 2, bar, 3, baz)));
        assertThat(rocksDb.sortedIndexes(1), is(Map.of(4, quux)));

        // Put some data in the CF. We then check that the non-empty CF is restored upon DB restart but the empty one is dropped.
        byte[] key = ByteBuffer.allocate(Integer.BYTES * 2)
                .order(KEY_BYTE_ORDER)
                .putInt(0)
                .putInt(1)
                .array();

        foo.put(key, BYTE_EMPTY_ARRAY);

        rocksDb.stop();

        rocksDb = createDb();

        assertThat(rocksDb.sortedIndexes(0), allOf(hasKey(1), not(hasKey(2)), not(hasKey(3))));
        assertThat(rocksDb.sortedIndexes(1), is(anEmptyMap()));

        assertTrue(cfExists(fooName));
        assertFalse(cfExists(barName));
    }

    @Test
    void testHashIndexRecovery() throws Exception {
        assertThat(rocksDb.hashIndexIds(2), is(empty()));
        assertThat(rocksDb.hashIndexIds(4), is(empty()));
        assertThat(rocksDb.hashIndexIds(5), is(empty()));

        // Put some data in the CF. We then check that the non-empty CF is restored upon DB restart but the empty one is dropped.
        byte[] key1 = ByteBuffer.allocate(Integer.BYTES * 2)
                .order(KEY_BYTE_ORDER)
                .putInt(2)
                .putInt(1)
                .array();

        byte[] key2 = ByteBuffer.allocate(Integer.BYTES * 2)
                .order(KEY_BYTE_ORDER)
                .putInt(4)
                .putInt(3)
                .array();

        rocksDb.hashIndexCf().put(key1, BYTE_EMPTY_ARRAY);
        rocksDb.hashIndexCf().put(key2, BYTE_EMPTY_ARRAY);

        rocksDb.stop();

        rocksDb = createDb();

        assertThat(rocksDb.hashIndexIds(2), contains(1));
        assertThat(rocksDb.hashIndexIds(4), contains(3));
        assertThat(rocksDb.hashIndexIds(5), is(empty()));
    }

    private boolean cfExists(byte[] cfName) {
        try {
            // Check Column Family existence by trying to create a new one with the same name.
            ColumnFamilyHandle handle = rocksDb.db.createColumnFamily(new ColumnFamilyDescriptor(cfName));

            rocksDb.db.destroyColumnFamilyHandle(handle);

            return false;
        } catch (RocksDBException e) {
            return true;
        }
    }
}
