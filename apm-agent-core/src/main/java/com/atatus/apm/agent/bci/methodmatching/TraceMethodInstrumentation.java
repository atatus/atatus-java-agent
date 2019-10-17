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
package com.atatus.apm.agent.bci.methodmatching;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import javax.annotation.Nullable;

import com.atatus.apm.agent.bci.AtatusApmInstrumentation;
import com.atatus.apm.agent.bci.bytebuddy.SimpleMethodSignatureOffsetMappingFactory;
import com.atatus.apm.agent.configuration.CoreConfiguration;
import com.atatus.apm.agent.impl.AtatusApmTracer;
import com.atatus.apm.agent.impl.transaction.AbstractSpan;
import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.impl.transaction.TraceContext;
import com.atatus.apm.agent.impl.transaction.TraceContextHolder;
import com.atatus.apm.agent.matcher.WildcardMatcher;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.atatus.apm.agent.bci.bytebuddy.CustomElementMatchers.matches;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public class TraceMethodInstrumentation extends AtatusApmInstrumentation {

    public static long traceMethodThresholdMicros;

    protected final MethodMatcher methodMatcher;

    public TraceMethodInstrumentation(AtatusApmTracer tracer, MethodMatcher methodMatcher) {
        this.methodMatcher = methodMatcher;
        traceMethodThresholdMicros = tracer.getConfig(CoreConfiguration.class).getTraceMethodsDurationThreshold().getMillis() * 1000;
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethodEnter(@Advice.Origin Class<?> clazz,
                                     @SimpleMethodSignatureOffsetMappingFactory.SimpleMethodSignature String signature,
                                     @Advice.Local("span") AbstractSpan<?> span) {
        if (tracer != null) {
            final TraceContextHolder<?> parent = tracer.getActive();
            if (parent == null) {
                span = tracer.startTransaction(TraceContext.asRoot(), null, clazz.getClassLoader())
                    .withName(signature)
                    .activate();
            } else {
                span = parent.createSpan()
                    .withName(signature)
                    .activate();

                // by default discard such spans
                span.setDiscard(true);
            }
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onMethodExit(@Advice.Local("span") @Nullable AbstractSpan<?> span,
                                    @Advice.Thrown @Nullable Throwable t) {
        if (span != null) {
            span.captureException(t);
            final long endTime = span.getTraceContext().getClock().getEpochMicros();
            if (span instanceof Span) {
                long durationMicros = endTime - span.getTimestamp();
                if (traceMethodThresholdMicros <= 0 || durationMicros >= traceMethodThresholdMicros || t != null) {
                    span.setDiscard(false);
                }
            }
            span.deactivate().end(endTime);
        }
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return matches(methodMatcher.getClassMatcher())
            .and(methodMatcher.getAnnotationMatcher())
            .and(not(nameContains("$JaxbAccessor")))
            .and(not(nameContains("$$")))
            .and(not(nameContains("CGLIB")))
            .and(not(nameContains("EnhancerBy")))
            .and(not(nameContains("$Proxy")))
            .and(declaresMethod(matches(methodMatcher.getMethodMatcher())));
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        ElementMatcher.Junction<? super MethodDescription> matcher = matches(methodMatcher.getMethodMatcher());

        final List<WildcardMatcher> methodsExcludedFromInstrumentation =
            (tracer != null)? tracer.getConfig(CoreConfiguration.class).getMethodsExcludedFromInstrumentation(): null;
        if (methodsExcludedFromInstrumentation != null && !methodsExcludedFromInstrumentation.isEmpty()) {
            matcher = matcher.and(not(new ElementMatcher<MethodDescription>() {
                @Override
                public boolean matches(MethodDescription target) {
                    return WildcardMatcher.anyMatch(methodsExcludedFromInstrumentation, target.getActualName()) != null;
                }
            }));
        }

        if (methodMatcher.getModifier() != null) {
            switch (methodMatcher.getModifier()) {
                case Modifier.PUBLIC:
                    matcher = matcher.and(ElementMatchers.isPublic());
                    break;
                case Modifier.PROTECTED:
                    matcher = matcher.and(ElementMatchers.isProtected());
                    break;
                case Modifier.PRIVATE:
                    matcher = matcher.and(ElementMatchers.isPrivate());
                    break;
            }
        }
        if (methodMatcher.getArgumentMatchers() != null) {
            matcher = matcher.and(takesArguments(methodMatcher.getArgumentMatchers().size()));
            List<WildcardMatcher> argumentMatchers = methodMatcher.getArgumentMatchers();
            for (int i = 0; i < argumentMatchers.size(); i++) {
                matcher = matcher.and(takesArgument(i, matches(argumentMatchers.get(i))));
            }
        }
        // Byte Buddy can't catch exceptions (onThrowable = Throwable.class) during a constructor call:
        // java.lang.IllegalStateException: Cannot catch exception during constructor call
        return matcher.and(not(isConstructor()))
            .and(not(isAbstract()))
            .and(not(isNative()))
            .and(not(isSynthetic()))
            .and(not(isTypeInitializer()));
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Collections.singletonList("method-matching");
    }
}
