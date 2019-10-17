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
package com.atatus.apm.agent.servlet;

import com.atatus.apm.agent.MockReporter;
import com.atatus.apm.agent.MockTracer;
import com.atatus.apm.agent.configuration.SpyConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stagemonitor.configuration.ConfigurationRegistry;

import com.atatus.apm.agent.impl.AtatusApmTracerBuilder;
import com.atatus.apm.agent.impl.transaction.Transaction;
import com.atatus.apm.agent.matcher.WildcardMatcher;
import com.atatus.apm.agent.servlet.ServletTransactionHelper;
import com.atatus.apm.agent.web.WebConfiguration;

import javax.annotation.Nonnull;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ServletTransactionHelperTest {

    private ServletTransactionHelper servletTransactionHelper;
    private WebConfiguration webConfig;

    @BeforeEach
    void setUp() {
        ConfigurationRegistry config = SpyConfiguration.createSpyConfig();
        webConfig = config.getConfig(WebConfiguration.class);
        servletTransactionHelper = new ServletTransactionHelper(new AtatusApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(new MockReporter())
            .build());
    }

    @Test
    void setTransactionNameByServletClass() {
        Transaction transaction = new Transaction(MockTracer.create());
        ServletTransactionHelper.setTransactionNameByServletClass("GET", ServletTransactionHelperTest.class, transaction);
        assertThat(transaction.getNameAsString()).isEqualTo("ServletTransactionHelperTest#doGet");
    }

    @Test
    void setTransactionNameByServletClassNullMethod() {
        Transaction transaction = new Transaction(MockTracer.create());
        ServletTransactionHelper.setTransactionNameByServletClass(null, ServletTransactionHelperTest.class, transaction);
        assertThat(transaction.getNameAsString()).isEqualTo("ServletTransactionHelperTest");
    }

    @Test
    void testGroupUrls() {
        when(webConfig.isUsePathAsName()).thenReturn(true);
        when(webConfig.getUrlGroups()).thenReturn(List.of(
            WildcardMatcher.valueOf("/foo/bar/*/qux"),
            WildcardMatcher.valueOf("/foo/bar/*")
        ));

        assertThat(getTransactionName("GET", "/foo/bar/baz")).isEqualTo("GET /foo/bar/*");
        assertThat(getTransactionName("POST", "/foo/bar/baz/qux")).isEqualTo("POST /foo/bar/*/qux");
        assertThat(getTransactionName("GET", "/foo/bar/baz/quux")).isEqualTo("GET /foo/bar/*");
        assertThat(getTransactionName("GET", "/foo/bar/baz/quux/qux")).isEqualTo("GET /foo/bar/*/qux");
    }

    @Test
    void testGroupUrlsOverridesServletName() {
        when(webConfig.isUsePathAsName()).thenReturn(true);
        when(webConfig.getUrlGroups()).thenReturn(List.of(
            WildcardMatcher.valueOf("/foo/bar/*")
        ));

        Transaction transaction = new Transaction(MockTracer.create());
        ServletTransactionHelper.setTransactionNameByServletClass("GET", ServletTransactionHelperTest.class, transaction);
        servletTransactionHelper.applyDefaultTransactionName("GET", "/foo/bar/baz", null, transaction);
        assertThat(transaction.getNameAsString()).isEqualTo("GET /foo/bar/*");
    }

    @Nonnull
    private String getTransactionName(String method, String path) {
        Transaction transaction = new Transaction(MockTracer.create());
        servletTransactionHelper.applyDefaultTransactionName(method, path, null, transaction);
        return transaction.getNameAsString();
    }
}
