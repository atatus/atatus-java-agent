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
package com.atatus.apm.agent.es.restclient;

import org.apache.http.HttpEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;

import com.atatus.apm.agent.bci.ElasticApmInstrumentation;
import com.atatus.apm.agent.bci.HelperClassManager;
import com.atatus.apm.agent.bci.VisibleForAdvice;
import com.atatus.apm.agent.impl.ElasticApmTracer;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;


public abstract class ElasticsearchRestClientInstrumentation extends ElasticApmInstrumentation {

    @Nullable
    @VisibleForAdvice
    // Referencing ES classes is legal due to type erasure. The field must be public in order for it to be accessible from injected code
    public static HelperClassManager<ElasticsearchRestClientInstrumentationHelper<HttpEntity, Response, ResponseListener>> esClientInstrHelperManager;

    public ElasticsearchRestClientInstrumentation(ElasticApmTracer tracer) {
        esClientInstrHelperManager = HelperClassManager.ForAnyClassLoader.of(tracer,
            "com.atatus.apm.agent.es.restclient.ElasticsearchRestClientInstrumentationHelperImpl",
            "com.atatus.apm.agent.es.restclient.ResponseListenerWrapper",
            "com.atatus.apm.agent.es.restclient.ElasticsearchRestClientInstrumentationHelperImpl$ResponseListenerAllocator");
    }

    @Override
    public Collection<String> getInstrumentationGroupNames() {
        return Collections.singleton("elasticsearch-restclient");
    }
}
