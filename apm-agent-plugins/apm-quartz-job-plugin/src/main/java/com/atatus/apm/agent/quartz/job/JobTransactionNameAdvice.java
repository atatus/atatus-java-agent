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
package com.atatus.apm.agent.quartz.job;

import net.bytebuddy.asm.Advice;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atatus.apm.agent.bci.AtatusApmInstrumentation;
import com.atatus.apm.agent.bci.VisibleForAdvice;
import com.atatus.apm.agent.bci.bytebuddy.SimpleMethodSignatureOffsetMappingFactory.SimpleMethodSignature;
import com.atatus.apm.agent.impl.transaction.TraceContext;
import com.atatus.apm.agent.impl.transaction.TraceContextHolder;
import com.atatus.apm.agent.impl.transaction.Transaction;

import javax.annotation.Nullable;

public class JobTransactionNameAdvice {
    @VisibleForAdvice
    public static final Logger logger = LoggerFactory.getLogger(JobTransactionNameInstrumentation.class);

    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void setTransactionName(@Advice.Argument(value = 0) @Nullable JobExecutionContext context,
                                           @SimpleMethodSignature String signature, @Advice.Origin Class<?> clazz, @Advice.Local("transaction") Transaction transaction) {
        if (AtatusApmInstrumentation.tracer != null) {
            TraceContextHolder<?> active = AtatusApmInstrumentation.tracer.getActive();
            if (context == null) {
                logger.warn("Cannot correctly name transaction for method {} because JobExecutionContext is null", signature);
                transaction = AtatusApmInstrumentation.tracer.startTransaction(TraceContext.asRoot(), null, clazz.getClassLoader())
                    .withName(signature)
                    .withType(JobTransactionNameInstrumentation.TRANSACTION_TYPE)
                    .activate();
            } else if (active == null) {
                transaction = AtatusApmInstrumentation.tracer.startTransaction(TraceContext.asRoot(), null, clazz.getClassLoader())
                    .withName(context.getJobDetail().getKey().toString())
                    .withType(JobTransactionNameInstrumentation.TRANSACTION_TYPE)
                    .activate();
            } else {
                logger.debug("Not creating transaction for method {} because there is already a transaction running ({})", signature, active);
            }
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onMethodExitException(@Advice.Argument(value = 0) @Nullable JobExecutionContext context,
                                             @Advice.Local("transaction") @Nullable Transaction transaction, @Advice.Thrown Throwable t) {
        if (transaction != null) {
            if (context != null && context.getResult() != null) {
                transaction.withResultIfUnset(context.getResult().toString());
            }
            transaction.captureException(t)
                .deactivate()
                .end();
        }
    }
}
