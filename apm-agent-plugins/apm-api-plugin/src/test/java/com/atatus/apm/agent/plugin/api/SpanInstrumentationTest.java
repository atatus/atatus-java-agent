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
package com.atatus.apm.agent.plugin.api;

import com.atatus.apm.agent.AbstractInstrumentationTest;
import com.atatus.apm.agent.impl.transaction.TraceContext;
import com.atatus.apm.api.Atatus;
import com.atatus.apm.api.Scope;
import com.atatus.apm.api.Span;
import com.atatus.apm.api.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpanInstrumentationTest extends AbstractInstrumentationTest {

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        transaction = Atatus.startTransaction();
    }

    @Test
    void testSetName() {
        Span span = transaction.startSpan();
        span.setName("foo");
        endSpan(span);
        assertThat(reporter.getFirstSpan().getNameAsString()).isEqualTo("foo");
    }

    @Test
    void testLegacyAPIs() {
        Span span = transaction.createSpan();
        span.setType("foo.bar.baz");
        endSpan(span);
        com.atatus.apm.agent.impl.transaction.Span  internalSpan = reporter.getFirstSpan();
        assertThat(internalSpan.getType()).isEqualTo("foo");
        assertThat(internalSpan.getSubtype()).isEqualTo("bar");
        assertThat(internalSpan.getAction()).isEqualTo("baz");
    }

    @Test
    void testTypes() {
        Span span = transaction.startSpan("foo", "bar", "baz");
        endSpan(span);
        com.atatus.apm.agent.impl.transaction.Span  internalSpan = reporter.getFirstSpan();
        assertThat(internalSpan.getType()).isEqualTo("foo");
        assertThat(internalSpan.getSubtype()).isEqualTo("bar");
        assertThat(internalSpan.getAction()).isEqualTo("baz");
    }

    @Test
    void testChaining() {
        Span span = transaction.startSpan("foo", null, null).setName("foo").addLabel("foo", "bar");
        endSpan(span);
        assertThat(reporter.getFirstSpan().getNameAsString()).isEqualTo("foo");
        assertThat(reporter.getFirstSpan().getType()).isEqualTo("foo");
        assertThat(reporter.getFirstSpan().getContext().getLabel("foo")).isEqualTo("bar");
    }

    private void endSpan(Span span) {
        span.end();
        transaction.end();
        assertThat(reporter.getSpans()).hasSize(1);
        assertThat(reporter.getTransactions()).hasSize(1);
    }

    @Test
    void testScope() {
        Span span = transaction.startSpan();
        assertThat(Atatus.currentSpan().getId()).isNotEqualTo(span.getId());
        try (final Scope scope = span.activate()) {
            assertThat(Atatus.currentSpan().getId()).isEqualTo(span.getId());
            Atatus.currentSpan().startSpan().end();
        }
        span.end();
        transaction.end();
        assertThat(reporter.getSpans()).hasSize(2);
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getSpans().get(0).isChildOf(reporter.getSpans().get(1))).isTrue();
        assertThat(reporter.getSpans().get(1).isChildOf(reporter.getFirstTransaction())).isTrue();
    }

    @Test
    void testSampled() {
        assertThat(Atatus.currentSpan().isSampled()).isFalse();
        assertThat(Atatus.currentTransaction().isSampled()).isFalse();
        final Transaction transaction = Atatus.startTransaction();
        assertThat(transaction.isSampled()).isTrue();
        assertThat(transaction.startSpan().isSampled()).isTrue();
    }

    @Test
    void testTraceHeadersNoop() {
        assertContainsNoTracingHeaders(Atatus.currentSpan());
        assertContainsNoTracingHeaders(Atatus.currentTransaction());
    }

    @Test
    void testTraceHeaders() {
        Span span = transaction.startSpan();
        assertContainsTracingHeaders(span);
        assertContainsTracingHeaders(transaction);
    }

    private void assertContainsNoTracingHeaders(Span span) {
        try (Scope scope = span.activate()) {
            final Map<String, String> tracingHeaders = new HashMap<>();
            span.injectTraceHeaders(tracingHeaders::put);
            span.injectTraceHeaders(null);
            assertThat(tracingHeaders).isEmpty();
        }
    }

    private void assertContainsTracingHeaders(Span span) {
        try (Scope scope = span.activate()) {
            final Map<String, String> tracingHeaders = new HashMap<>();
            span.injectTraceHeaders(tracingHeaders::put);
            span.injectTraceHeaders(null);
            final String traceparent = tracer.getActive().getTraceContext().getOutgoingTraceParentHeader().toString();
            assertThat(tracingHeaders).containsEntry(TraceContext.TRACE_PARENT_HEADER, traceparent);
        }
    }
}
