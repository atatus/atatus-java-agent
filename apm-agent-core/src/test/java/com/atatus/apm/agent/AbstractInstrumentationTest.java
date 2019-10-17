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
package com.atatus.apm.agent;

import com.atatus.apm.agent.configuration.SpyConfiguration;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.stagemonitor.configuration.ConfigurationRegistry;

import com.atatus.apm.agent.bci.AtatusApmAgent;
import com.atatus.apm.agent.impl.AtatusApmTracer;
import com.atatus.apm.agent.impl.AtatusApmTracerBuilder;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractInstrumentationTest {
    protected static AtatusApmTracer tracer;
    protected static MockReporter reporter;
    protected static ConfigurationRegistry config;

    @BeforeAll
    @BeforeClass
    public static void beforeAll() {
        reporter = new MockReporter();
        config = SpyConfiguration.createSpyConfig();
        tracer = new AtatusApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .build();
        AtatusApmAgent.initInstrumentation(tracer, ByteBuddyAgent.install());
    }

    @AfterAll
    @AfterClass
    public static void afterAll() {
        AtatusApmAgent.reset();
    }

    public static void reset() {
        SpyConfiguration.reset(config);
        reporter.reset();
    }

    public static AtatusApmTracer getTracer() {
        return tracer;
    }

    public static MockReporter getReporter() {
        return reporter;
    }

    public static ConfigurationRegistry getConfig() {
        return config;
    }

    @Before
    @BeforeEach
    public final void resetReporter() {
        reset();
    }

    @After
    @AfterEach
    public final void cleanUp() {
        tracer.resetServiceNameOverrides();
        assertThat(tracer.getActive()).isNull();
    }
}
