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

package org.apache.ignite.internal.tx.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.internal.network.NetworkMessage;
import org.apache.ignite.internal.network.annotations.Transferable;
import org.apache.ignite.internal.replicator.ReplicationGroupId;
import org.apache.ignite.internal.replicator.message.ReplicationGroupIdMessage;

/** Message for transferring a {@link CleanupReplicatedInfo}. */
@Transferable(TxMessageGroup.CLEANUP_REPLICATED_INFO_MESSAGE)
public interface CleanupReplicatedInfoMessage extends NetworkMessage {
    /** Transaction ID. */
    UUID txId();

    /** Partitions. */
    List<ReplicationGroupIdMessage> partitions();

    /** Converts to {@link CleanupReplicatedInfo}. */
    default CleanupReplicatedInfo asCleanupReplicatedInfo() {
        List<ReplicationGroupIdMessage> partitionMessages = partitions();
        List<ReplicationGroupId> partitions = new ArrayList<>(partitionMessages.size());

        for (ReplicationGroupIdMessage partitionMessage : partitionMessages) {
            partitions.add(partitionMessage.asReplicationGroupId());
        }

        return new CleanupReplicatedInfo(txId(), partitions);
    }
}
