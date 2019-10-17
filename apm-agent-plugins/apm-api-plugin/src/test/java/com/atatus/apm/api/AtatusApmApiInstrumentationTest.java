/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2019 Elastic and contributors
 * %%
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * #L%
 */
package com.atatus.apm.api;

import com.atatus.apm.agent.AbstractInstrumentationTest;
import com.atatus.apm.agent.configuration.CoreConfiguration;
import com.atatus.apm.agent.impl.Scope;
import com.atatus.apm.agent.impl.sampling.ConstantSampler;
import com.atatus.apm.agent.impl.transaction.TraceContext;
import org.junit.jupiter.api.Test;

import com.atatus.apm.api.AtatusApm;
import com.atatus.apm.api.NoopSpan;
import com.atatus.apm.api.NoopTransaction;
import com.atatus.apm.api.Span;
import com.atatus.apm.api.Transaction;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class AtatusApmApiInstrumentationTest extends AbstractInstrumentationTest {

    @Test
    void testCreateTransaction() {
        assertThat(AtatusApm.startTransaction()).isNotSameAs(NoopTransaction.INSTANCE);
        assertThat(AtatusApm.currentTransaction()).isSameAs(NoopTransaction.INSTANCE);
    }

    @Test
    void testNoCurrentTransaction() {
        assertThat(AtatusApm.currentTransaction()).isSameAs(NoopTransaction.INSTANCE);
    }

    @Test
    void testLegacyTransactionCreateSpan() {
        assertThat(AtatusApm.startTransaction().createSpan()).isNotSameAs(NoopSpan.INSTANCE);
        assertThat(AtatusApm.currentSpan()).isSameAs(NoopSpan.INSTANCE);
    }

    @Test
    void testStartSpan() {
        assertThat(AtatusApm.startTransaction().startSpan()).isNotSameAs(NoopSpan.INSTANCE);
        assertThat(AtatusApm.currentSpan()).isSameAs(NoopSpan.INSTANCE);
    }

    @Test
    void testNoCurrentSpan() {
        assertThat(AtatusApm.currentSpan()).isSameAs(NoopSpan.INSTANCE);
    }

    @Test
    void testCaptureException() {
        AtatusApm.captureException(new RuntimeException("Bazinga"));
        assertThat(reporter.getErrors()).hasSize(1);
    }

    @Test
    void testCaptureExceptionNoopSpan() {
        AtatusApm.currentSpan().captureException(new RuntimeException("Bazinga"));
        assertThat(reporter.getErrors()).hasSize(1);
    }

    @Test
    void testTransactionWithError() {
        final Transaction transaction = AtatusApm.startTransaction();
        transaction.setType("request");
        transaction.setName("transaction");
        transaction.captureException(new RuntimeException("Bazinga"));
        transaction.end();
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getErrors()).hasSize(1);
    }

    @Test
    void testCreateChildSpanOfCurrentTransaction() {
        final com.atatus.apm.agent.impl.transaction.Transaction transaction = tracer.startTransaction(TraceContext.asRoot(), null, null).withType("request").withName("transaction").activate();
        final Span span = AtatusApm.currentSpan().startSpan("db", "mysql", "query");
        span.setName("span");
        span.end();
        transaction.deactivate().end();
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getSpans()).hasSize(1);
        com.atatus.apm.agent.impl.transaction.Span internalSpan = reporter.getFirstSpan();
        assertThat(internalSpan.getTraceContext().getParentId()).isEqualTo(reporter.getFirstTransaction().getTraceContext().getId());
        assertThat(internalSpan.getType()).isEqualTo("db");
        assertThat(internalSpan.getSubtype()).isEqualTo("mysql");
        assertThat(internalSpan.getAction()).isEqualTo("query");
    }

    @Test
    void testLegacySpanCreationAndTyping() {
        final com.atatus.apm.agent.impl.transaction.Transaction transaction = tracer.startTransaction(TraceContext.asRoot(), null, null).withType("request").withName("transaction").activate();
        final Span span = AtatusApm.currentSpan().createSpan();
        span.setName("span");
        span.setType("db.mysql.query.etc");
        span.end();
        transaction.deactivate().end();
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getSpans()).hasSize(1);
        com.atatus.apm.agent.impl.transaction.Span internalSpan = reporter.getFirstSpan();
        assertThat(internalSpan.getTraceContext().getParentId()).isEqualTo(reporter.getFirstTransaction().getTraceContext().getId());
        assertThat(internalSpan.getType()).isEqualTo("db");
        assertThat(internalSpan.getSubtype()).isEqualTo("mysql");
        assertThat(internalSpan.getAction()).isEqualTo("query.etc");
    }

    // https://github.com/elastic/apm-agent-java/issues/132
    @Test
    void testAutomaticAndManualTransactions() {
        final com.atatus.apm.agent.impl.transaction.Transaction transaction = tracer.startTransaction(TraceContext.asRoot(), null, null).withType("request").withName("transaction").activate();
        final Transaction manualTransaction = AtatusApm.startTransaction();
        manualTransaction.setName("manual transaction");
        manualTransaction.setType("request");
        manualTransaction.end();
        transaction.deactivate().end();
        assertThat(reporter.getTransactions()).hasSize(2);
    }

    @Test
    void testGetId_distributedTracingEnabled() {
        com.atatus.apm.agent.impl.transaction.Transaction transaction = tracer.startTransaction(TraceContext.asRoot(), null, null).withType(Transaction.TYPE_REQUEST);
        try (Scope scope = transaction.activateInScope()) {
            assertThat(AtatusApm.currentTransaction().getId()).isEqualTo(transaction.getTraceContext().getId().toString());
            assertThat(AtatusApm.currentTransaction().getTraceId()).isEqualTo(transaction.getTraceContext().getTraceId().toString());
            assertThat(AtatusApm.currentSpan().getId()).isEqualTo(transaction.getTraceContext().getId().toString());
            assertThat(AtatusApm.currentSpan().getTraceId()).isEqualTo(transaction.getTraceContext().getTraceId().toString());
            com.atatus.apm.agent.impl.transaction.Span span = transaction.createSpan().withType("db").withName("SELECT");
            try (Scope spanScope = span.activateInScope()) {
                assertThat(AtatusApm.currentSpan().getId()).isEqualTo(span.getTraceContext().getId().toString());
                assertThat(AtatusApm.currentSpan().getTraceId()).isEqualTo(span.getTraceContext().getTraceId().toString());
            } finally {
                span.end();
            }
        } finally {
            transaction.end();
        }
    }

    @Test
    void testGetId_noop() {
        assertThat(AtatusApm.currentTransaction().getId()).isEmpty();
        assertThat(AtatusApm.currentSpan().getId()).isEmpty();
    }

    @Test
    void testAddLabel() {
        Transaction transaction = AtatusApm.startTransaction();
        transaction.setName("foo");
        transaction.setType("bar");
        transaction.addLabel("foo1", "bar1");
        transaction.addLabel("foo", "bar");
        transaction.addLabel("number", 1);
        transaction.addLabel("boolean", true);
        transaction.addLabel("null", (String) null);
        Span span = transaction.startSpan("bar", null, null);
        span.setName("foo");
        span.addLabel("bar1", "baz1");
        span.addLabel("bar", "baz");
        span.addLabel("number", 1);
        span.addLabel("boolean", true);
        span.addLabel("null", (String) null);
        span.end();
        transaction.end();
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getSpans()).hasSize(1);
        assertThat(reporter.getFirstTransaction().getContext().getLabel("foo1")).isEqualTo("bar1");
        assertThat(reporter.getFirstTransaction().getContext().getLabel("foo")).isEqualTo("bar");
        assertThat(reporter.getFirstTransaction().getContext().getLabel("number")).isEqualTo(1);
        assertThat(reporter.getFirstTransaction().getContext().getLabel("boolean")).isEqualTo(true);
        assertThat(reporter.getFirstTransaction().getContext().getLabel("null")).isNull();
        assertThat(reporter.getFirstSpan().getContext().getLabel("bar1")).isEqualTo("baz1");
        assertThat(reporter.getFirstSpan().getContext().getLabel("bar")).isEqualTo("baz");
        assertThat(reporter.getFirstSpan().getContext().getLabel("number")).isEqualTo(1);
        assertThat(reporter.getFirstSpan().getContext().getLabel("boolean")).isEqualTo(true);
        assertThat(reporter.getFirstSpan().getContext().getLabel("null")).isNull();
    }

    @Test
    void testAddCustomContext() {
        Transaction transaction = AtatusApm.startTransaction();
        transaction.setName("foo");
        transaction.setType("bar");
        transaction.addCustomContext("foo1", "bar1");
        transaction.addCustomContext("foo", "bar");
        transaction.addCustomContext("number", 1);
        transaction.addCustomContext("boolean", true);
        transaction.addCustomContext("null", (String) null);
        transaction.end();
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getFirstTransaction().getContext().getCustom("foo1")).isEqualTo("bar1");
        assertThat(reporter.getFirstTransaction().getContext().getCustom("foo")).isEqualTo("bar");
        assertThat(reporter.getFirstTransaction().getContext().getCustom("number")).isEqualTo(1);
        assertThat(reporter.getFirstTransaction().getContext().getCustom("boolean")).isEqualTo(true);
        assertThat(reporter.getFirstTransaction().getContext().getCustom("null")).isNull();
    }

    @Test
    void testScopes() {
        Transaction transaction = AtatusApm.startTransaction();
        try (com.atatus.apm.api.Scope scope = transaction.activate()) {
            assertThat(AtatusApm.currentTransaction().getId()).isEqualTo(transaction.getId());
            Span span = transaction.startSpan();
            try (com.atatus.apm.api.Scope spanScope = span.activate()) {
                assertThat(AtatusApm.currentSpan().getId()).isEqualTo(span.getId());
            } finally {
                span.end();
            }
            assertThat(AtatusApm.currentSpan().getId()).isEqualTo(transaction.getId());
        } finally {
            transaction.end();
        }
        assertThat(AtatusApm.currentTransaction()).isSameAs(NoopTransaction.INSTANCE);

    }

    @Test
    void testTraceContextScopes() {
        com.atatus.apm.agent.impl.transaction.Transaction transaction = tracer.startTransaction(TraceContext.asRoot(), null, getClass().getClassLoader());
        tracer.activate(transaction.getTraceContext());
        final Span span = AtatusApm.currentSpan();
        assertThat(tracer.getActive()).isInstanceOf(TraceContext.class);
        tracer.deactivate(transaction.getTraceContext());
        assertThat(tracer.getActive()).isNull();
        try (com.atatus.apm.api.Scope activate = span.activate()) {
            assertThat(tracer.getActive()).isInstanceOf(TraceContext.class);
        }
    }

    @Test
    void testEnsureParentId() {
        final Transaction transaction = AtatusApm.startTransaction();
        try (com.atatus.apm.api.Scope scope = transaction.activate()) {
            assertThat(tracer.currentTransaction()).isNotNull();
            assertThat(tracer.currentTransaction().getTraceContext().getParentId().isEmpty()).isTrue();
            String rumTransactionId = transaction.ensureParentId();
            assertThat(tracer.currentTransaction().getTraceContext().getParentId().toString()).isEqualTo(rumTransactionId);
            assertThat(transaction.ensureParentId()).isEqualTo(rumTransactionId);
        }
    }

    @Test
    void testTransactionWithRemoteParentFunction() {
        final TraceContext parent = TraceContext.with64BitId(tracer);
        parent.asRootSpan(ConstantSampler.of(true));
        AtatusApm.startTransactionWithRemoteParent(key -> parent.getOutgoingTraceParentHeader().toString()).end();
        assertThat(reporter.getFirstTransaction().getTraceContext().isChildOf(parent)).isTrue();
    }

    @Test
    void testTransactionWithRemoteParentFunctions() {
        final TraceContext parent = TraceContext.with64BitId(tracer);
        parent.asRootSpan(ConstantSampler.of(true));
        final Map<String, String> map = Map.of(TraceContext.TRACE_PARENT_HEADER, parent.getOutgoingTraceParentHeader().toString());
        AtatusApm.startTransactionWithRemoteParent(map::get, key -> Collections.singletonList(map.get(key))).end();
        assertThat(reporter.getFirstTransaction().getTraceContext().isChildOf(parent)).isTrue();
    }

    @Test
    void testTransactionWithRemoteParentHeaders() {
        final TraceContext parent = TraceContext.with64BitId(tracer);
        parent.asRootSpan(ConstantSampler.of(true));
        final Map<String, String> map = Map.of(TraceContext.TRACE_PARENT_HEADER, parent.getOutgoingTraceParentHeader().toString());
        AtatusApm.startTransactionWithRemoteParent(null, key -> Collections.singletonList(map.get(key))).end();
        assertThat(reporter.getFirstTransaction().getTraceContext().isChildOf(parent)).isTrue();
    }

    @Test
    void testTransactionWithRemoteParentNullFunction() {
        AtatusApm.startTransactionWithRemoteParent(null).end();
        assertThat(reporter.getFirstTransaction().getTraceContext().isRoot()).isTrue();
    }

    @Test
    void testTransactionWithRemoteParentNullFunctions() {
        AtatusApm.startTransactionWithRemoteParent(null, null).end();
        assertThat(reporter.getFirstTransaction().getTraceContext().isRoot()).isTrue();
    }

    @Test
    void testManualTimestamps() {
        final Transaction transaction = AtatusApm.startTransaction().setStartTimestamp(0);
        transaction.startSpan().setStartTimestamp(1000).end(2000);
        transaction.end(3000);

        assertThat(reporter.getFirstTransaction().getDuration()).isEqualTo(3000);
        assertThat(reporter.getFirstSpan().getDuration()).isEqualTo(1000);
    }

    @Test
    void testManualTimestampsDeactivated() {
        when(config.getConfig(CoreConfiguration.class).isActive()).thenReturn(false);
        final Transaction transaction = AtatusApm.startTransaction().setStartTimestamp(0);
        transaction.startSpan().setStartTimestamp(1000).end(2000);
        transaction.end(3000);

        assertThat(reporter.getTransactions()).hasSize(0);
        assertThat(reporter.getSpans()).hasSize(0);
    }

    @Test
    void testOverrideServiceNameForClassLoader() {
        tracer.overrideServiceNameForClassLoader(Transaction.class.getClassLoader(), "overridden");
        AtatusApm.startTransaction().end();
        assertThat(reporter.getFirstTransaction().getTraceContext().getServiceName()).isEqualTo("overridden");
    }

    @Test
    void testOverrideServiceNameForClassLoaderWithRemoteParent() {
        tracer.overrideServiceNameForClassLoader(Transaction.class.getClassLoader(), "overridden");
        AtatusApm.startTransactionWithRemoteParent(key -> null).end();
        assertThat(reporter.getFirstTransaction().getTraceContext().getServiceName()).isEqualTo("overridden");
    }
}
