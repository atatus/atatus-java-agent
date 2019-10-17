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
package com.atatus.apm.agent.spring.webmvc.template;

import com.atatus.apm.agent.MockReporter;
import com.atatus.apm.agent.configuration.SpyConfiguration;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.stagemonitor.configuration.ConfigurationRegistry;

import com.atatus.apm.agent.bci.AtatusApmAgent;
import com.atatus.apm.agent.impl.AtatusApmTracer;
import com.atatus.apm.agent.impl.AtatusApmTracerBuilder;
import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.servlet.ServletInstrumentation;
import com.atatus.apm.agent.spring.webmvc.ViewRenderInstrumentation;

import javax.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration("src/test/resources")
@TestConfiguration
abstract class AbstractViewRenderingInstrumentationTest {

    protected static AtatusApmTracer tracer;
    protected static MockReporter reporter;
    protected static ConfigurationRegistry config;
    protected MockMvc mockMvc;

    @Autowired
    WebApplicationContext wac;

    @BeforeAll
    static void beforeAll() {
        reporter = new MockReporter();
        config = SpyConfiguration.createSpyConfig();
        tracer = new AtatusApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .build();
        AtatusApmAgent.initInstrumentation(tracer, ByteBuddyAgent.install(), Arrays.asList(new ServletInstrumentation(tracer), new ViewRenderInstrumentation()));
    }

    @AfterAll
    static void afterAll() {
        AtatusApmAgent.reset();
    }

    @BeforeEach
    void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @AfterEach
    final void cleanUp() {
        tracer.resetServiceNameOverrides();
        assertThat(tracer.getActive()).isNull();
    }

    void verifySpanCapture(String spanSubType, String spanSuffix, MockHttpServletResponse response, @Nullable String messageContent) throws UnsupportedEncodingException {
        assertEquals(200, response.getStatus());
        String responseString = response.getContentAsString();
        assertEquals(messageContent, responseString.trim());
        assertEquals(1, reporter.getSpans().size());
        Span firstSpan = reporter.getSpans().get(0);
        assertEquals("template", firstSpan.getType());
        assertEquals(spanSubType, firstSpan.getSubtype());
        assertEquals("render", firstSpan.getAction());
        assertEquals("View#render" + spanSuffix, firstSpan.getNameAsString());
    }
}
