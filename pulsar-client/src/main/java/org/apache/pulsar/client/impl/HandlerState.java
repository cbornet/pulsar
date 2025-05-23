/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.StringUtils;

abstract class HandlerState {
    protected final PulsarClientImpl client;
    protected final String topic;
    protected volatile URI redirectedClusterURI;

    private static final AtomicReferenceFieldUpdater<HandlerState, State> STATE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(HandlerState.class, State.class, "state");
    @SuppressWarnings("unused")
    private volatile State state = null;

    enum State {
        Uninitialized, // Not initialized
        Connecting, // Client connecting to broker
        Ready, // Handler is being used
        Closing, // Close cmd has been sent to broker
        Closed, // Broker acked the close
        Terminated, // Topic associated with this handler
                    // has been terminated
        Failed, // Handler is failed
        RegisteringSchema, // Handler is registering schema
        ProducerFenced, // The producer has been fenced by the broker
    };

    public HandlerState(PulsarClientImpl client, String topic) {
        this.client = client;
        this.topic = topic;
        STATE_UPDATER.set(this, State.Uninitialized);
    }

    protected void setRedirectedClusterURI(String serviceUrl, String serviceUrlTls) throws URISyntaxException {
        String url = client.conf.isUseTls() && StringUtils.isNotBlank(serviceUrlTls) ? serviceUrlTls : serviceUrl;
        this.redirectedClusterURI = new URI(url);
    }

    // moves the state to ready if it wasn't closed
    protected boolean changeToReadyState() {
        if (STATE_UPDATER.get(this) == State.Ready) {
            return true;
        }
        return (STATE_UPDATER.compareAndSet(this, State.Uninitialized, State.Ready)
                || STATE_UPDATER.compareAndSet(this, State.Connecting, State.Ready)
                || STATE_UPDATER.compareAndSet(this, State.RegisteringSchema, State.Ready));
    }

    protected boolean compareAndSetState(State expect, State update) {
        return STATE_UPDATER.compareAndSet(this, expect, update);
    }

    protected boolean changeToRegisteringSchemaState() {
        return STATE_UPDATER.compareAndSet(this, State.Ready, State.RegisteringSchema);
    }

    protected State getState() {
        return STATE_UPDATER.get(this);
    }

    protected boolean changeToConnecting() {
        if (STATE_UPDATER.get(this) == State.Connecting) {
            return true;
        }
        return (STATE_UPDATER.compareAndSet(this, State.Uninitialized, State.Connecting)
                || STATE_UPDATER.compareAndSet(this, State.Ready, State.Connecting)
                || STATE_UPDATER.compareAndSet(this, State.RegisteringSchema, State.Connecting));
    }

    protected void setState(State s) {
        STATE_UPDATER.set(this, s);
    }

    abstract String getHandlerName();

    protected State getAndUpdateState(final UnaryOperator<State> updater) {
        return STATE_UPDATER.getAndUpdate(this, updater);
    }

    public PulsarClientImpl getClient() {
        return client;
    }
}
