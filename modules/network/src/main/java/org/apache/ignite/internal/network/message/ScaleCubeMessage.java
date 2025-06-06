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

package org.apache.ignite.internal.network.message;

import io.scalecube.cluster.transport.api.Message;
import java.util.Map;
import org.apache.ignite.internal.network.NetworkMessage;
import org.apache.ignite.internal.network.NetworkMessageTypes;
import org.apache.ignite.internal.network.annotations.Marshallable;
import org.apache.ignite.internal.network.annotations.Transferable;
import org.jetbrains.annotations.Nullable;

/**
 * Wrapper for ScaleCube's {@link Message}.
 *
 * <p>{@link Message#data()} is stored in {@link #data} or {@link #message} depending on the type of
 * the data (whether it is a {@link NetworkMessage} or not) and {@link Message#headers()} are stored in {@link #headers}.
 */
@Transferable(NetworkMessageTypes.SCALE_CUBE_MESSAGE)
public interface ScaleCubeMessage extends NetworkMessage {
    @Nullable
    @Marshallable
    Object data();

    @Nullable
    NetworkMessage message();

    Map<String, String> headers();

    @Override
    default String toStringForLightLogging() {
        Object data = data();

        String dataString = data == null ? "null" : data.getClass().getName();

        NetworkMessage message = message();

        String messageString = message == null ? "null" : message.toStringForLightLogging();

        return getClass().getName() + ": [data=" + dataString + ", message=" + messageString + "]";
    }
}
