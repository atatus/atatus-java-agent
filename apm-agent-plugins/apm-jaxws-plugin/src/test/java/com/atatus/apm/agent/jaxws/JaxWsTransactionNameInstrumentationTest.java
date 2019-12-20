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
package com.atatus.apm.agent.jaxws;

import com.atatus.apm.agent.AbstractInstrumentationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atatus.apm.agent.impl.Scope;
import com.atatus.apm.agent.impl.transaction.TraceContext;
import com.atatus.apm.agent.impl.transaction.Transaction;

import javax.jws.WebMethod;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;

import static org.assertj.core.api.Assertions.assertThat;

class JaxWsTransactionNameInstrumentationTest extends AbstractInstrumentationTest {

    private HelloWorldService helloWorldService;

    @BeforeEach
    void setUp() {
        helloWorldService = new HelloWorldServiceImpl();
    }

    @Test
    void testTransactionName() {
        final Transaction transaction = tracer.startTransaction(TraceContext.asRoot(), null, getClass().getClassLoader());
        try (Scope scope = transaction.activateInScope()) {
            helloWorldService.sayHello();
        } finally {
            transaction.end();
        }
        assertThat(transaction.getNameAsString()).isEqualTo("HelloWorldServiceImpl#sayHello");
    }

    @SOAPBinding(style = SOAPBinding.Style.RPC)
    @WebService(targetNamespace = "atatus")
    public interface HelloWorldService {
        @WebMethod
        String sayHello();
    }

    @WebService(serviceName = "HelloWorldService", portName = "HelloWorld", name = "HelloWorld",
        endpointInterface = "com.atatus.apm.agent.jaxws.JaxWsTransactionNameInstrumentationTest.HelloWorldService",
        targetNamespace = "atatus")
    public static class HelloWorldServiceImpl implements HelloWorldService {
        @Override
        public String sayHello() {
            return "Hello World";
        }
    }

}
