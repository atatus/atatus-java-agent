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
package com.atatus.apm.agent.jdbc;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import javax.annotation.Nullable;

import com.atatus.apm.agent.bci.AtatusApmInstrumentation;
import com.atatus.apm.agent.bci.HelperClassManager;
import com.atatus.apm.agent.bci.VisibleForAdvice;
import com.atatus.apm.agent.impl.AtatusApmTracer;
import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.jdbc.helper.JdbcHelper;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;

import static com.atatus.apm.agent.jdbc.ConnectionInstrumentation.JDBC_INSTRUMENTATION_GROUP;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Creates spans for JDBC calls
 */
public abstract class StatementInstrumentation extends AtatusApmInstrumentation {

    @SuppressWarnings("WeakerAccess")
    @Nullable
    @VisibleForAdvice
    public static HelperClassManager<JdbcHelper> jdbcHelperManager;

    private final ElementMatcher<? super MethodDescription> methodMatcher;

    StatementInstrumentation(AtatusApmTracer tracer, ElementMatcher<? super MethodDescription> methodMatcher) {
        this.methodMatcher = methodMatcher;
        jdbcHelperManager = HelperClassManager.ForSingleClassLoader.of(tracer,
            "com.atatus.apm.agent.jdbc.helper.JdbcHelperImpl",
            "com.atatus.apm.agent.jdbc.helper.JdbcHelperImpl$1",
            "com.atatus.apm.agent.jdbc.helper.JdbcHelperImpl$ConnectionMetaData");
    }

    @Override
    public ElementMatcher<? super NamedElement> getTypeMatcherPreFilter() {
        return nameContains("Statement");
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return not(isInterface())
            .and(hasSuperType(named("java.sql.Statement")));
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return methodMatcher;
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Collections.singleton(JDBC_INSTRUMENTATION_GROUP);
    }

    public static class ExecuteWithQueryInstrumentation extends StatementInstrumentation {

        public ExecuteWithQueryInstrumentation(AtatusApmTracer tracer) {
            super(tracer,
                nameStartsWith("execute")
                    .and(takesArgument(0, String.class))
                    .and(isPublic())
            );
        }

        @Nullable
        @VisibleForAdvice
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Span onBeforeExecute(@Advice.This Statement statement, @Advice.Argument(0) String sql) throws SQLException {
            if (tracer != null && jdbcHelperManager != null) {
                JdbcHelper helperImpl = jdbcHelperManager.getForClassLoaderOfClass(Statement.class);
                if (helperImpl != null) {
                    return helperImpl.createJdbcSpan(sql, statement.getConnection(), tracer.getActive(), false);
                }
            }
            return null;
        }

        @VisibleForAdvice
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onAfterExecute(@Advice.Enter @Nullable Span span, @Advice.Thrown Throwable t) {
            if (span != null) {
                span.captureException(t)
                    .deactivate()
                    .end();
            }
        }
    }

    public static class AddBatchInstrumentation extends StatementInstrumentation {

        public AddBatchInstrumentation(AtatusApmTracer tracer) {
            super(tracer,
                nameStartsWith("addBatch")
                    .and(takesArgument(0, String.class))
                    .and(isPublic())
            );
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void storeSql(@Advice.This Statement statement, @Advice.Argument(0) String sql) {
            if (jdbcHelperManager != null) {
                JdbcHelper helperImpl = jdbcHelperManager.getForClassLoaderOfClass(Statement.class);
                if (helperImpl != null) {
                    helperImpl.mapStatementToSql(statement, sql);
                }
            }
        }
    }

    public static class ExecuteWithoutQueryInstrumentation extends StatementInstrumentation {
        public ExecuteWithoutQueryInstrumentation(AtatusApmTracer tracer) {
            super(tracer,
                nameStartsWith("execute")
                    .and(takesArguments(0))
                    .and(isPublic())
            );
        }

        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Span onBeforeExecute(@Advice.This Statement statement) throws SQLException {
            if (tracer != null && jdbcHelperManager != null) {
                JdbcHelper helperImpl = jdbcHelperManager.getForClassLoaderOfClass(Statement.class);
                if (helperImpl != null) {
                    final @Nullable String sql = helperImpl.retrieveSqlForStatement(statement);
                    return helperImpl.createJdbcSpan(sql, statement.getConnection(), tracer.getActive(), true);
                }
            }
            return null;
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onAfterExecute(@Advice.Enter @Nullable Span span, @Advice.Thrown Throwable t) {
            if (span != null) {
                span.captureException(t)
                    .deactivate()
                    .end();
            }
        }
    }
}
