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
package com.atatus.apm.agent.bci;

import com.atatus.apm.agent.MockTracer;
import com.atatus.apm.agent.configuration.CoreConfiguration;
import com.atatus.apm.agent.configuration.SpyConfiguration;
import com.atatus.apm.agent.impl.AtatusApmTracer;
import com.atatus.apm.agent.impl.AtatusApmTracerBuilder;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.commons.math.util.MathUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.stagemonitor.configuration.ConfigurationRegistry;

import com.atatus.apm.agent.bci.AtatusApmAgent;
import com.atatus.apm.agent.bci.AtatusApmInstrumentation;

import java.util.Collection;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class InstrumentationTest {

    private final AtatusApmTracer tracer = MockTracer.create();

    @AfterEach
    void afterAll() {
        AtatusApmAgent.reset();
    }

    @Test
    void testIntercept() {
        init(SpyConfiguration.createSpyConfig());
        assertThat(interceptMe()).isEqualTo("intercepted");
    }

    @Test
    void testDisabled() {
        final ConfigurationRegistry config = SpyConfiguration.createSpyConfig();
        when(config.getConfig(CoreConfiguration.class).getDisabledInstrumentations()).thenReturn(Collections.singletonList("test"));
        init(config);
        assertThat(interceptMe()).isEmpty();
    }

    @Test
    void testDontInstrumentOldClassFileVersions() {
        AtatusApmAgent.initInstrumentation(tracer,
            ByteBuddyAgent.install(),
            Collections.singletonList(new MathInstrumentation()));
        // if the instrumentation applied, it would return 42
        // but instrumenting old class file versions could lead to VerifyErrors in some cases and possibly some more shenanigans
        // so we we are better off not touching Java 1.4 code at all
        assertThat(MathUtils.sign(-42)).isEqualTo(-1);
    }

    @Test
    void testSuppressException() {
        AtatusApmAgent.initInstrumentation(tracer,
            ByteBuddyAgent.install(),
            Collections.singletonList(new SuppressExceptionInstrumentation()));
        assertThat(noExceptionPlease("foo")).isEqualTo("foo_no_exception");
    }

    @Test
    void testRetainExceptionInUserCode() {
        AtatusApmAgent.initInstrumentation(tracer,
            ByteBuddyAgent.install(),
            Collections.singletonList(new SuppressExceptionInstrumentation()));
        assertThatThrownBy(this::exceptionPlease).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNonSuppressedException() {
        AtatusApmAgent.initInstrumentation(tracer,
            ByteBuddyAgent.install(),
            Collections.singletonList(new ExceptionInstrumentation()));
        assertThatThrownBy(() -> noExceptionPlease("foo")).isInstanceOf(RuntimeException.class);
    }

    String noExceptionPlease(String s) {
        return s + "_no_exception";
    }

    String exceptionPlease() {
        throw null;
    }

    private void init(ConfigurationRegistry config) {
        AtatusApmAgent.initInstrumentation(new AtatusApmTracerBuilder()
                .configurationRegistry(config)
                .build(),
            ByteBuddyAgent.install(),
            Collections.singletonList(new TestInstrumentation()));
    }

    private String interceptMe() {
        return "";
    }

    public static class TestInstrumentation extends AtatusApmInstrumentation {
        @Advice.OnMethodExit
        public static void onMethodExit(@Advice.Return(readOnly = false) String returnValue) {
            returnValue = "intercepted";
        }

        @Override
        public ElementMatcher<? super TypeDescription> getTypeMatcher() {
            return ElementMatchers.named("com.atatus.apm.agent.bci.InstrumentationTest");
        }

        @Override
        public ElementMatcher<? super MethodDescription> getMethodMatcher() {
            return ElementMatchers.named("interceptMe");
        }

        @Override
        public Collection<String> getInstrumentationGroupNames() {
            return Collections.singleton("test");
        }
    }

    public static class MathInstrumentation extends AtatusApmInstrumentation {
        @Advice.OnMethodExit
        public static void onMethodExit(@Advice.Return(readOnly = false) int returnValue) {
            returnValue = 42;
        }

        @Override
        public ElementMatcher<? super TypeDescription> getTypeMatcher() {
            return ElementMatchers.named("org.apache.commons.math.util.MathUtils");
        }

        @Override
        public ElementMatcher<? super MethodDescription> getMethodMatcher() {
            return ElementMatchers.named("sign").and(ElementMatchers.takesArguments(int.class));
        }

        @Override
        public Collection<String> getInstrumentationGroupNames() {
            return Collections.emptyList();
        }
    }

    public static class ExceptionInstrumentation extends AtatusApmInstrumentation {
        @Advice.OnMethodExit
        public static void onMethodExit() {
            throw new RuntimeException("This exception should not be suppressed");
        }

        @Override
        public ElementMatcher<? super TypeDescription> getTypeMatcher() {
            return ElementMatchers.named(InstrumentationTest.class.getName());
        }

        @Override
        public ElementMatcher<? super MethodDescription> getMethodMatcher() {
            return ElementMatchers.nameEndsWithIgnoreCase("exceptionPlease");
        }

        @Override
        public Collection<String> getInstrumentationGroupNames() {
            return Collections.emptyList();
        }
    }

    public static class SuppressExceptionInstrumentation extends AtatusApmInstrumentation {
        @Advice.OnMethodExit(suppress = Throwable.class)
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onMethodEnterAndExit() {
            throw new RuntimeException("This exception should be suppressed");
        }

        @Override
        public ElementMatcher<? super TypeDescription> getTypeMatcher() {
            return ElementMatchers.named(InstrumentationTest.class.getName());
        }

        @Override
        public ElementMatcher<? super MethodDescription> getMethodMatcher() {
            return ElementMatchers.named("noExceptionPlease");
        }

        @Override
        public Collection<String> getInstrumentationGroupNames() {
            return Collections.emptyList();
        }
    }
}
