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

package com.atatus.apm.agent.collector.serialize;

import com.atatus.apm.agent.collector.payload.ErrorMetricPayload;
import com.atatus.apm.agent.collector.payload.ErrorMetricStatusCodePayload;
import com.atatus.apm.agent.collector.payload.ErrorRequestPayload;
import com.atatus.apm.agent.collector.payload.RequestPayload;
import com.atatus.apm.agent.collector.payload.SpanPayload;
import com.atatus.apm.agent.collector.payload.TracePayload;
import com.atatus.apm.agent.collector.payload.TraceSpanPayload;
import com.atatus.apm.agent.collector.payload.TransactionPayload;

import com.atatus.apm.agent.impl.MetaData;
import com.atatus.apm.agent.impl.context.AbstractContext;
import com.atatus.apm.agent.impl.context.Request;
import com.atatus.apm.agent.impl.context.Response;
import com.atatus.apm.agent.impl.context.Socket;
import com.atatus.apm.agent.impl.context.SpanContext;
import com.atatus.apm.agent.impl.context.TransactionContext;
import com.atatus.apm.agent.impl.context.Url;
import com.atatus.apm.agent.impl.context.User;
import com.atatus.apm.agent.impl.error.ErrorCapture;
import com.atatus.apm.agent.impl.payload.Agent;
import com.atatus.apm.agent.impl.payload.Framework;
import com.atatus.apm.agent.impl.payload.Language;
import com.atatus.apm.agent.impl.payload.ProcessInfo;
import com.atatus.apm.agent.impl.payload.RuntimeInfo;
import com.atatus.apm.agent.impl.payload.Service;
import com.atatus.apm.agent.impl.payload.SystemInfo;
import com.atatus.apm.agent.impl.stacktrace.StacktraceConfiguration;
import com.atatus.apm.agent.impl.transaction.AbstractSpan;
import com.atatus.apm.agent.impl.transaction.Db;
import com.atatus.apm.agent.impl.transaction.Http;
import com.atatus.apm.agent.impl.transaction.Id;
import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.impl.transaction.SpanCount;
import com.atatus.apm.agent.impl.transaction.TraceContext;
import com.atatus.apm.agent.impl.transaction.Transaction;
import com.atatus.apm.agent.metrics.Labels;
import com.atatus.apm.agent.metrics.MetricRegistry;
import com.atatus.apm.agent.metrics.MetricSet;
import com.atatus.apm.agent.report.ApmServerClient;
import com.atatus.apm.agent.report.ReporterConfiguration;
import com.atatus.apm.agent.report.serialize.MetricRegistrySerializer;
import com.atatus.apm.agent.report.serialize.PayloadSerializer;
import com.atatus.apm.agent.util.PotentiallyMultiValuedMap;
import com.dslplatform.json.BoolConverter;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.NumberConverter;
import com.dslplatform.json.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.dslplatform.json.JsonWriter.ARRAY_END;
import static com.dslplatform.json.JsonWriter.ARRAY_START;
import static com.dslplatform.json.JsonWriter.COMMA;
import static com.dslplatform.json.JsonWriter.OBJECT_END;
import static com.dslplatform.json.JsonWriter.OBJECT_START;

public class JsonSerializer implements PayloadSerializer, MetricRegistry.MetricsReporter {

    /**
     * Matches default ZLIB buffer size.
     * Lets us assume the ZLIB buffer is always empty,
     * so that {@link #getBufferSize()} is the total amount of buffered bytes.
     */
    public static final int BUFFER_SIZE = 16384;
    static final int MAX_VALUE_LENGTH = 1024;
    public static final int MAX_LONG_STRING_VALUE_LENGTH = 10000;
    private static final byte NEW_LINE = (byte) '\n';
    private static final Logger logger = LoggerFactory.getLogger(JsonSerializer.class);
    private static final String[] DISALLOWED_IN_LABEL_KEY = new String[]{".", "*", "\""};
    // visible for testing
    final JsonWriter jw;
    private final Collection<String> excludedStackFrames = Arrays.asList("java.lang.reflect", "com.sun", "sun.", "jdk.internal.");
    private final StringBuilder replaceBuilder = new StringBuilder(MAX_LONG_STRING_VALUE_LENGTH + 1);
    private final StacktraceConfiguration stacktraceConfiguration;
    private final ApmServerClient apmServerClient;
    @Nullable
    private OutputStream os;

    public JsonSerializer(StacktraceConfiguration stacktraceConfiguration, ApmServerClient apmServerClient) {
        this.stacktraceConfiguration = stacktraceConfiguration;
        this.apmServerClient = apmServerClient;
        jw = new DslJson<>(new DslJson.Settings<>()).newWriter(BUFFER_SIZE);
    }

    @Override
    public void setOutputStream(final OutputStream os) {
        if (logger.isDebugEnabled()) {
            this.os = new ByteArrayOutputStream() {
                @Override
                public void flush() throws IOException {
                    logger.debug("Atatus: FLUSHING buffer....");
                    os.write(buf, 0, size());
                    os.flush();
                    logger.debug(new String(buf, 0, size(), Charset.forName("UTF-8")));
                }
            };
        } else {
            this.os = os;
        }
        jw.reset(this.os);
    }

    @Override
    public void flush() throws IOException {
        jw.flush();
        try {
            logger.debug("Atatus: FLUSHING OS....{}", os);
            if (os != null) {
                os.flush();
            }
        } finally {
            jw.reset();
        }
    }

    @Override
    public void serializeTransactionNdJson(Transaction transaction) {
    }

    @Override
    public void serializeSpanNdJson(Span span) {
    }

    @Override
    public void serializeErrorNdJson(ErrorCapture error) {
    }

    @Override
    public void serializeMetaDataNdJson(MetaData metaData) {
        jw.writeByte(JsonWriter.OBJECT_START);
        writeFieldName("metadata");
        serializeMetadata(metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        jw.writeByte(NEW_LINE);
    }

    @Override
	public void serializeMetadata(MetaData metaData) {
        jw.writeByte(JsonWriter.OBJECT_START);
        serializeService(metaData.getService());
        jw.writeByte(COMMA);
        serializeProcess(metaData.getProcess());
        jw.writeByte(COMMA);
        serializeGlobalLabels(metaData.getGlobalLabelKeys(), metaData.getGlobalLabelValues());
        serializeSystem(metaData.getSystem());
        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeGlobalLabels(ArrayList<String> globalLabelKeys, ArrayList<String> globalLabelValues) {
        if (!globalLabelKeys.isEmpty()) {
            writeFieldName("labels");
            jw.writeByte(OBJECT_START);
            writeStringValue(sanitizeLabelKey(globalLabelKeys.get(0), replaceBuilder), replaceBuilder, jw);
            jw.writeByte(JsonWriter.SEMI);
            writeStringValue(globalLabelValues.get(0), replaceBuilder, jw);
            for (int i = 0; i < globalLabelKeys.size(); i++) {
                jw.writeByte(COMMA);
                writeStringValue(sanitizeLabelKey(globalLabelKeys.get(i), replaceBuilder), replaceBuilder, jw);
                jw.writeByte(JsonWriter.SEMI);
                writeStringValue(globalLabelValues.get(i), replaceBuilder, jw);
            }
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    /**
     * Returns the number of bytes already serialized and waiting in the underlying {@link JsonWriter}'s buffer.
     * Note that the resulting JSON can be bigger if a Stream is set to the writer and some data was already flushed
     *
     * @return number of bytes currently waiting in the underlying {@link JsonWriter} to be flushed to the underlying stream
     */
    @Override
    public int getBufferSize() {
        return jw.size();
    }

    @Override
    public void report(Map<? extends Labels, MetricSet> metricSets) {
        MetricRegistrySerializer.serialize(metricSets, replaceBuilder, jw);
    }

    @Override
    public void serializeMetrics(MetricRegistry metricRegistry) {
        metricRegistry.report(this);
    }

    public String toJsonHostInfo(final ReporterConfiguration reporterConfiguration,
    		final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);

        writeFieldName("environment");
        jw.writeByte(OBJECT_START);
        serializeHostDetails(metaData);
        jw.writeByte(COMMA);
        serializeAgentSettings(metaData, reporterConfiguration);
        jw.writeByte(COMMA);
        serializeJavaPackages(metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        jw.writeByte(COMMA);

        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonTransactions(final HashMap<String, TransactionPayload> transactionPayloads,
    		final ReporterConfiguration reporterConfiguration, final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        serializeTransactionPayloads(transactionPayloads);
        jw.writeByte(COMMA);
        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonTrace(final TracePayload tracePayload,
    		final ReporterConfiguration reporterConfiguration, final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        serializeTracePayloads(tracePayload);
        jw.writeByte(COMMA);
        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }
    
    public String toJsonTraces(final BlockingQueue<TracePayload> tracePayloads,
    		final ReporterConfiguration reporterConfiguration, final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        serializeTracePayloads(tracePayloads);
        jw.writeByte(COMMA);
        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonErrorMetrics(final ErrorMetricPayload errorMetricPayload,
    		final ReporterConfiguration reporterConfiguration, final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        serializeErrorMetricPayloads(errorMetricPayload.getErrorMetricStatusCodePayloadMap());
        jw.writeByte(COMMA);
        serializeErrorRequestPayloadQueue(errorMetricPayload.getErrorRequestPayloadQueue());
        jw.writeByte(COMMA);
        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonErrors(final BlockingQueue<ErrorCapture> errorQueue,
    		final ReporterConfiguration reporterConfiguration, final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        serializeErrors(errorQueue);
        jw.writeByte(COMMA);
        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonMetrics(BlockingQueue<MetricRegistry> metricsQueue,
    		final ReporterConfiguration reporterConfiguration, final MetaData metaData) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        writeFieldName("java");
        jw.writeByte(JsonWriter.ARRAY_START);

        int i = 0;
        while (!metricsQueue.isEmpty()) {
            try {
            	if (i > 0) {
            		jw.writeByte(COMMA);
            	}
            	i++;
            	jw.writeByte(JsonWriter.OBJECT_START);
            	serializeMetrics(metricsQueue.take());
            	jw.writeByte(JsonWriter.OBJECT_END);
            } catch (InterruptedException e) {
                // Exception handling.
            }
        }

        jw.writeByte(JsonWriter.ARRAY_END);
        jw.writeByte(COMMA);
        serializeCommonInfo(reporterConfiguration, metaData);
        jw.writeByte(JsonWriter.OBJECT_END);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonString(final ErrorCapture error) {
        jw.reset();
        jw.writeByte(OBJECT_START);
        serializeError(error);
        jw.writeByte(OBJECT_START);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toJsonString(final StackTraceElement stackTraceElement) {
        jw.reset();
        serializeStackTraceElement(stackTraceElement);
        final String s = jw.toString();
        jw.reset();
        return s;
    }

    public String toString() {
        return jw.toString();
    }

    private void serializeCommonInfo(final ReporterConfiguration reporterConfiguration, final MetaData metaData) {

    	Service service = metaData.getService();

        writeField("startTime", new Date().getTime());
        writeField("endTime", new Date().getTime());
        writeField("timestamp", new Date().getTime());

    	serializeCredentials(reporterConfiguration);
    	serializeAgent(service.getAgent());
    	serializeSystem(metaData.getSystem());

        writeField("version", service.getVersion());
        writeLastField("releaseStage", service.getEnvironment());
    }

    private void serializeCredentials(final ReporterConfiguration reporterConfiguration) {
        writeField("licenseKey", reporterConfiguration.getLicenseKey());
        writeField("appName", reporterConfiguration.getAppName());
    }

    private void serializeAgent(final Agent agent) {
        writeFieldName("agent");
        jw.writeByte(JsonWriter.OBJECT_START);
        writeField("name", agent.getName());
        writeLastField("version", agent.getVersion());
        jw.writeByte(JsonWriter.OBJECT_END);
        jw.writeByte(COMMA);
    }

    private void serializeSystem(final SystemInfo system) {

        writeField("hostname", system.getHostname());

        if (system.getBootId() != null) {
        	writeField("uniqueHostname", system.getBootId());

        } else if (system.getProductId() != null) {
        	writeField("uniqueHostname", system.getProductId());

        } else {
        	writeField("uniqueHostname", system.getHostname());
        }

        writeField("containerId", system.getContainerId());

        // serializeKubernetesInfo(system.getKubernetesInfo());
        // writeField("architecture", system.getArchitecture());
        // writeLastField("platform", system.getPlatform());
    }

    private void serializeService(final Service service) {
        writeFieldName("service");
        jw.writeByte(JsonWriter.OBJECT_START);

        writeField("name", service.getName());
        writeField("environment", service.getEnvironment());

        final Agent agent = service.getAgent();
        if (agent != null) {
            serializeAgent(agent);
        }

        final Framework framework = service.getFramework();
        if (framework != null) {
            serializeFramework(framework);
        }

        final Language language = service.getLanguage();
        if (language != null) {
            serializeLanguage(language);
        }

        final RuntimeInfo runtime = service.getRuntime();
        if (runtime != null) {
            serializeRuntime(runtime);
        }

        writeLastField("version", service.getVersion());
        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeFramework(final Framework framework) {
        writeFieldName("framework");
        jw.writeByte(JsonWriter.OBJECT_START);
        writeField("name", framework.getName());
        writeLastField("version", framework.getVersion());
        jw.writeByte(JsonWriter.OBJECT_END);
        jw.writeByte(COMMA);
    }

    private void serializeLanguage(final Language language) {
        writeFieldName("language");
        jw.writeByte(JsonWriter.OBJECT_START);
        writeField("name", language.getName());
        writeLastField("version", language.getVersion());
        jw.writeByte(JsonWriter.OBJECT_END);
        jw.writeByte(COMMA);
    }

    private void serializeRuntime(final RuntimeInfo runtime) {
        writeFieldName("runtime");
        jw.writeByte(JsonWriter.OBJECT_START);
        writeField("name", runtime.getName());
        writeLastField("version", runtime.getVersion());
        jw.writeByte(JsonWriter.OBJECT_END);
        jw.writeByte(COMMA);
    }

    private void serializeProcess(final ProcessInfo process) {
        writeFieldName("process");
        jw.writeByte(JsonWriter.OBJECT_START);
        writeField("pid", process.getPid());
        if (process.getPpid() != null) {
            writeField("ppid", process.getPpid());
        }

        List<String> argv = process.getArgv();
        writeField("argv", argv);
        writeLastField("title", process.getTitle());
        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeKubernetesInfo(@Nullable SystemInfo.Kubernetes kubernetes) {
        if (kubernetes != null && kubernetes.hasContent()) {
            writeFieldName("kubernetes");
            jw.writeByte(JsonWriter.OBJECT_START);
            serializeKubeNodeInfo(kubernetes.getNode());
            serializeKubePodInfo(kubernetes.getPod());
            writeLastField("namespace", kubernetes.getNamespace());
            jw.writeByte(JsonWriter.OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeKubePodInfo(@Nullable SystemInfo.Kubernetes.Pod pod) {
        if (pod != null) {
            writeFieldName("pod");
            jw.writeByte(JsonWriter.OBJECT_START);
            String podName = pod.getName();
            if (podName != null) {
                writeField("name", podName);
            }
            writeLastField("uid", pod.getUid());
            jw.writeByte(JsonWriter.OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeKubeNodeInfo(@Nullable SystemInfo.Kubernetes.Node node) {
        if (node != null) {
            writeFieldName("node");
            jw.writeByte(JsonWriter.OBJECT_START);
            writeLastField("name", node.getName());
            jw.writeByte(JsonWriter.OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeDurations(Long[] durations) {
        if (durations.length > 0) {
            writeFieldName("durations");
            jw.writeByte(ARRAY_START);
            NumberConverter.serialize(durations[0], jw);
            for (int i = 1; i < durations.length; i++) {
                jw.writeByte(COMMA);
                NumberConverter.serialize(durations[i] / TimeUnit.MILLISECONDS.toMicros(1), jw);
            }
            jw.writeByte(ARRAY_END);
        }
    }

    private void serializeHostDetails(final MetaData metaData) {
        writeFieldName("host");
        jw.writeByte(JsonWriter.OBJECT_START);

        SystemInfo systemInfo = metaData.getSystem();
        writeField("os", systemInfo.getOS());
        writeField("version", systemInfo.getVersion());
        writeField("platform", systemInfo.getPlatform());
        writeField("kernel", systemInfo.getKernel());
        writeField("arch", systemInfo.getArchitecture());
        writeField("hostname", systemInfo.getHostname());
        writeField("productId", systemInfo.getProductId());
        writeField("bootId", systemInfo.getBootId());
        writeField("hostId", systemInfo.getHostId());
        writeField("containerId", systemInfo.getContainerId());
        writeField("ram", systemInfo.getTotalMem());

        int cpuCount = systemInfo.getCpuCount();
        writeFieldName("cpu");
        jw.writeByte(JsonWriter.ARRAY_START);
        for (int i = 1; i <= cpuCount; i++) {
        	jw.writeByte(JsonWriter.OBJECT_START);
        	writeField("cores", i);
        	writeField("mhz", systemInfo.getCpuFrequencyInMHz());
        	writeLastField("model", systemInfo.getCpuModelName());
        	jw.writeByte(JsonWriter.OBJECT_END);
        	if (i != cpuCount) {
        		jw.writeByte(COMMA);
        	}
        }
        jw.writeByte(JsonWriter.ARRAY_END);
        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeAgentSettings(final MetaData metaData, final ReporterConfiguration reporterConfiguration) {
        writeFieldName("settings");
        jw.writeByte(JsonWriter.OBJECT_START);

        SystemInfo system = metaData.getSystem();
        if (system != null) {
            writeField("java_version", system.getJavaVersion());
            writeField("java_vendor", system.getJavaVendor());
            writeField("java_vm", system.getJavaVMName());
            writeField("java_vm_version", system.getJavaVMVersion());
        }
        writeField("java_max_memory", Runtime.getRuntime().maxMemory());

        Service service = metaData.getService();
        if (service != null) {

            writeField("environment", service.getEnvironment());
        	writeField("agent_version", service.getAgent().getVersion());

        	Framework framework = service.getFramework();
        	if (framework != null) {
                writeField("framework", framework.getName());
                writeField("framework_version", framework.getVersion());
        	} else {
        		writeField("framework", "Java");
        	}

        	RuntimeInfo runtimeInfo = service.getRuntime();
        	if (framework != null) {
                writeField("runtime", runtimeInfo.getName());
                writeField("runtime_version", runtimeInfo.getVersion());
        	}
        }

        // writeField("disable_metrics", reporterConfiguration.getDisableMetrics());
        // writeField("metrics_interval", reporterConfiguration.getMetricsIntervalMs());
        // writeField("trace_threshold", "");
        // writeField("log_level", "");

        writeLastField("app_name", reporterConfiguration.getAppName());

        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeJavaPackages(final MetaData metaData) {
        writeFieldName("java_pacakges");
        jw.writeByte(JsonWriter.OBJECT_START);
        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeTransactionPayloads(final HashMap<String, TransactionPayload> transactionPayloads) {

        // writeField("name", span.getNameForSerialization());
        // writeTimestamp(span.getTimestamp());
        // serializeTraceContext(span.getTraceContext(), true);
        // writeField("duration", span.getDurationMs());

        writeFieldName("transactions");
        jw.writeByte(ARRAY_START);

        int i = 0;
        for (Map.Entry mapElement : transactionPayloads.entrySet()) {
        	if (i > 0) {
        		jw.writeByte(COMMA);
        	}
        	i++;
        	serializeTransactionPayload((TransactionPayload)(mapElement.getValue()));
        }

        jw.writeByte(ARRAY_END);

        // serializeSpanContext(span.getContext(), span.getTraceContext());
        // serializeSpanType(span);
    }

    private void serializeTransactionPayload(final TransactionPayload transactionPayload) {
        jw.writeByte(OBJECT_START);
        writeField("name", transactionPayload.getName());
        writeField("type", transactionPayload.getType());
        writeField("kind", transactionPayload.getKind());
        writeField("background", transactionPayload.isBackground());
        serializeDurations(transactionPayload.getDurations());
        jw.writeByte(COMMA);

        serializeSpanPayloads(transactionPayload.getSpanPayloadMap());

        jw.writeByte(OBJECT_END);
    }

    private void serializeSpanPayloads(final HashMap<String, SpanPayload> spanPayloadMap) {
        writeFieldName("traces");
        jw.writeByte(ARRAY_START);

        int i = 0;
        for (Map.Entry mapElement : spanPayloadMap.entrySet()) {
        	if (i > 0) {
        		jw.writeByte(COMMA);
        	}
        	i++;
        	serializeSpanPayload((SpanPayload)(mapElement.getValue()));
        }

        jw.writeByte(ARRAY_END);
    }

    private void serializeSpanPayload(final SpanPayload spanPayload) {
        jw.writeByte(OBJECT_START);
        writeField("name", spanPayload.getName());
        writeField("type", spanPayload.getType());
        writeField("kind", spanPayload.getKind());
        serializeDurations(spanPayload.getDurations());
        jw.writeByte(OBJECT_END);
    }


    private void serializeTracePayloads(TracePayload tracePayload) {

        // writeField("name", span.getNameForSerialization());
        // writeTimestamp(span.getTimestamp());
        // serializeTraceContext(span.getTraceContext(), true);
        // writeField("duration", span.getDurationMs());

        writeFieldName("traces");
        jw.writeByte(ARRAY_START);
        serializeTracePayload(tracePayload);
        jw.writeByte(ARRAY_END);

    }
    
    private void serializeTracePayloads(final BlockingQueue<TracePayload> tracePayloadQueue) {

        // writeField("name", span.getNameForSerialization());
        // writeTimestamp(span.getTimestamp());
        // serializeTraceContext(span.getTraceContext(), true);
        // writeField("duration", span.getDurationMs());

        writeFieldName("traces");
        jw.writeByte(ARRAY_START);
        int i = 0;

        while (!tracePayloadQueue.isEmpty()) {
            try {
                TracePayload tracePayload = tracePayloadQueue.take();

            	if (i > 0) {
            		jw.writeByte(COMMA);
            	}
            	i++;
            	serializeTracePayload(tracePayload);

                // Do stuff.
            } catch (InterruptedException e) {
                // Exception handling.
            }
        }

        jw.writeByte(ARRAY_END);

    }

    private void serializeTracePayload(final TracePayload tracePayload) {
        jw.writeByte(OBJECT_START);
        writeField("name", tracePayload.getName());
        writeField("type", tracePayload.getType());
        writeField("kind", tracePayload.getType());
        writeField("duration", tracePayload.getDurationMs());
        writeField("funcs", tracePayload.getFunctions());
        serializeTraceSpans(tracePayload.getTraceSpans());
        jw.writeByte(COMMA);
        serializeContext(tracePayload.getContext());
        serializeRequestPayload(tracePayload.getRequestPayload());
        jw.writeByte(OBJECT_END);
    }

    private void serializeTraceSpans(final ArrayList<TraceSpanPayload> traceSpans) {
        writeFieldName("entries");
        jw.writeByte(ARRAY_START);

        for (int i = 0; i < traceSpans.size(); i++) {
        	if (i > 0) {
        		jw.writeByte(COMMA);
        	}
        	serializeTraceSpanPayload(traceSpans.get(i));
        }

        jw.writeByte(ARRAY_END);
    }

    private void serializeTraceSpanPayload(final TraceSpanPayload traceSpan) {
        jw.writeByte(OBJECT_START);
        writeField("i", traceSpan.getIndex());
        writeField("lv", traceSpan.getLevel());
        writeField("du", traceSpan.getDurationMs());
        writeField("so", traceSpan.getStartOffset());
        writeFieldName("ly");
        jw.writeByte(OBJECT_START);
        writeField("name", traceSpan.getName());
        writeField("type", traceSpan.getType());
        writeLastField("kind", traceSpan.getKind());
        jw.writeByte(OBJECT_END);
        jw.writeByte(COMMA);

        writeFieldName("dt");
        jw.writeByte(OBJECT_START);

        if (traceSpan.getStatement() != null) {

            if (traceSpan.getInstance() != null) {
            	writeField("dns", traceSpan.getInstance());
            }

            writeLongStringLastField("query", traceSpan.getStatement());

        } else if (traceSpan.getUrl() != null) {
        	writeField("url", traceSpan.getUrl());
        	writeField("method", traceSpan.getMethod());
        	writeLastField("statusCode", String.valueOf(traceSpan.getStatusCode()));
        }
        jw.writeByte(OBJECT_END);

        jw.writeByte(OBJECT_END);
    }

    private void serializeErrorMetricPayloads(final HashMap<String, ErrorMetricStatusCodePayload> errorMetricStatusCodePayloadMap) {

        writeFieldName("errorMetrics");
        jw.writeByte(ARRAY_START);

        int i = 0;
        for (Map.Entry mapElement : errorMetricStatusCodePayloadMap.entrySet()) {
            if (i > 0) {
                jw.writeByte(COMMA);
            }
            i++;
            serializeErrorMetricStatusCodePayload((ErrorMetricStatusCodePayload)(mapElement.getValue()));
        }

        jw.writeByte(ARRAY_END);

    }

    private void serializeErrorMetricStatusCodePayload(final ErrorMetricStatusCodePayload errorMetricStatusCodePayload) {
        jw.writeByte(OBJECT_START);
        writeField("name", errorMetricStatusCodePayload.getName());
        writeField("type", errorMetricStatusCodePayload.getType());
        writeField("kind", errorMetricStatusCodePayload.getKind());

        writeFieldName("statusCodes");
        jw.writeByte(OBJECT_START);
        int i = 0;
        int size = errorMetricStatusCodePayload.getStatusCodes().size();
        for (Map.Entry<Integer, Integer> mapElement : errorMetricStatusCodePayload.getStatusCodes().entrySet()) {
        	i++;
            if (i == size) {
            	writeLastField(mapElement.getKey().toString(), (int)mapElement.getValue());
            } else {
            	writeField(mapElement.getKey().toString(), (int)mapElement.getValue());
            }
        }
        jw.writeByte(OBJECT_END);

        jw.writeByte(OBJECT_END);
    }

    private void serializeErrorRequestPayloadQueue(BlockingQueue<ErrorRequestPayload> errorRequestPayloadQueue) {
    	writeFieldName("errorRequests");
        jw.writeByte(ARRAY_START);
        int i = 0;

        while (!errorRequestPayloadQueue.isEmpty()) {
            try {
            	ErrorRequestPayload errorRequestPayload = errorRequestPayloadQueue.take();

            	if (i > 0) {
            		jw.writeByte(COMMA);
            	}
            	i++;
            	serializeErrorRequestPayload(errorRequestPayload);

                // Do stuff.
            } catch (InterruptedException e) {
                // Exception handling.
            }
        }

        jw.writeByte(ARRAY_END);
    }

    private void serializeErrorRequestPayload(final ErrorRequestPayload errorRequestPayload) {
        jw.writeByte(OBJECT_START);
        writeField("name", errorRequestPayload.getName());
        writeField("type", errorRequestPayload.getType());
        writeField("kind", errorRequestPayload.getKind());
        serializeRequestPayload(errorRequestPayload.getRequestPayload());
        jw.writeByte(OBJECT_END);
    }

    private void serializeRequestPayload(RequestPayload requestPayload) {

        HashMap<String, Object> requestMap = requestPayload.getRequestMap();

        writeFieldName("request");
        jw.writeByte(OBJECT_START);
        int i = 0;
        for (Map.Entry<String, Object> mapElement : requestMap.entrySet()) {
        	i++;

        	Object value = (mapElement.getValue() != null) ? mapElement.getValue() : "";

            if (i == requestMap.size()) {
            	if (value instanceof Integer) {
            		writeLastField(mapElement.getKey().toString(), (int) value);
            	} else {
            		writeLastField(mapElement.getKey().toString(), String.valueOf(value));
            	}

            } else {
            	if (value instanceof Integer) {
            		writeField(mapElement.getKey().toString(), (int) value);
            	} else {
            		writeField(mapElement.getKey().toString(), String.valueOf(value));
            	}
            }
        }
        jw.writeByte(OBJECT_END);
    }


    private void serializeErrors(final BlockingQueue<ErrorCapture> errorQueue) {

        writeFieldName("errors");
        jw.writeByte(ARRAY_START);
        int i = 0;

        while (!errorQueue.isEmpty()) {
            try {
            	ErrorCapture errorCapture = errorQueue.take();

            	if (i > 0) {
            		jw.writeByte(COMMA);
            	}
            	i++;
            	serializeError(errorCapture);

            	errorCapture.recycle();

            } catch (InterruptedException e) {
                // Exception handling.
            }
        }

        jw.writeByte(ARRAY_END);

    }

    private void serializeError(ErrorCapture errorCapture) {
        jw.writeByte(JsonWriter.OBJECT_START);

        writeTimestamp(errorCapture.getTimestamp());
        serializeErrorTransactionInfo(errorCapture.getTransactionInfo());
        // if (errorCapture.getTraceContext().hasContent()) {
        //    serializeTraceContext(errorCapture.getTraceContext(), true);
        // }
        serializeContext(errorCapture.getContext());

        writeFieldName("exceptions");
        jw.writeByte(ARRAY_START);
        // writeField("culprit", errorCapture.getCulprit());
        serializeException(errorCapture.getException());
        jw.writeByte(ARRAY_END);

        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeErrorTransactionInfo(ErrorCapture.TransactionInfo errorTransactionInfo) {
    	writeField("transaction", errorTransactionInfo.getName());
        // if (errorTransactionInfo.getType() != null) {
        //    writeField("type", errorTransactionInfo.getType());
        // }
        // writeField("sampled", errorTransactionInfo.isSampled());
    }

    private void serializeException(@Nullable Throwable exception) {
        jw.writeByte(JsonWriter.OBJECT_START);
        if (exception != null) {
            writeField("message", String.valueOf(exception.getMessage()));
            serializeStacktrace(exception.getStackTrace());
            writeLastField("class", exception.getClass().getName());
        }
        jw.writeByte(JsonWriter.OBJECT_END);
    }

    private void serializeTraceContext(TraceContext traceContext, boolean serializeTransactionId) {
        // errors might only have an id
        writeHexField("id", traceContext.getId());
        if (!traceContext.getTraceId().isEmpty()) {
            writeHexField("trace_id", traceContext.getTraceId());
        }
        if (serializeTransactionId && !traceContext.getTransactionId().isEmpty()) {
            writeHexField("transaction_id", traceContext.getTransactionId());
        }
        if (!traceContext.getParentId().isEmpty()) {
            writeHexField("parent_id", traceContext.getParentId());
        }
    }

    private void serializeServiceName(TraceContext traceContext) {
        String serviceName = traceContext.getServiceName();
        if (serviceName != null) {
            writeFieldName("service");
            jw.writeByte(OBJECT_START);
            writeLastField("name", serviceName);
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    /**
     * TODO: remove in 2.0
     * To be removed for agents working only with APM server 7.0 or higher, where schema contains span.type, span.subtype and span.action
     * @param span serialized span
     */
    private void serializeSpanType(Span span) {
        writeFieldName("type");
        String type = span.getType();
        if (type != null) {
            replaceBuilder.setLength(0);
            replaceBuilder.append(type);
            replace(replaceBuilder, ".", "_", 0);
            String subtype = span.getSubtype();
            String action = span.getAction();
            if ((subtype != null && !subtype.isEmpty()) || (action != null && !action.isEmpty())) {
                replaceBuilder.append('.');
                int replaceStartIndex = replaceBuilder.length() + 1;
                if (subtype != null && !subtype.isEmpty()) {
                    replaceBuilder.append(subtype);
                    replace(replaceBuilder, ".", "_", replaceStartIndex);
                }
                if (action != null && !action.isEmpty()) {
                    replaceBuilder.append('.');
                    replaceStartIndex = replaceBuilder.length() + 1;
                    replaceBuilder.append(action);
                    replace(replaceBuilder, ".", "_", replaceStartIndex);
                }
            }
            writeStringValue(replaceBuilder);
        } else {
            jw.writeNull();
        }
    }

    private void serializeStacktrace(StackTraceElement[] stacktrace) {
        if (stacktrace.length > 0) {
            writeFieldName("stacktrace");
            jw.writeByte(ARRAY_START);
            serializeStackTraceArrayElements(stacktrace);
            jw.writeByte(ARRAY_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeStackTraceArrayElements(StackTraceElement[] stacktrace) {

        boolean topMostAtatusApmPackagesSkipped = false;
        int collectedStackFrames = 0;
        int stackTraceLimit = stacktraceConfiguration.getStackTraceLimit();
        for (int i = 0; i < stacktrace.length && collectedStackFrames < stackTraceLimit; i++) {
            StackTraceElement stackTraceElement = stacktrace[i];
            // only skip the top most apm stack frames
            if (!topMostAtatusApmPackagesSkipped && stackTraceElement.getClassName().startsWith("com.atatus.apm")) {
                continue;
            }
            topMostAtatusApmPackagesSkipped = true;

            if (isExcluded(stackTraceElement)) {
                continue;
            }

            if (collectedStackFrames > 0) {
                jw.writeByte(COMMA);
            }
            serializeStackTraceElement(stackTraceElement);
            collectedStackFrames++;
        }
    }

    private boolean isExcluded(StackTraceElement stackTraceElement) {
        // file name is a required field
        if (stackTraceElement.getFileName() == null) {
            return true;
        }
        String className = stackTraceElement.getClassName();
        for (String excludedStackFrame : excludedStackFrames) {
            if (className.startsWith(excludedStackFrame)) {
                return true;
            }
        }
        return false;
    }

    private void serializeStackTraceElement(StackTraceElement stacktrace) {
        jw.writeByte(OBJECT_START);
        writeField("f", stacktrace.getFileName());
        writeField("m", stacktrace.getMethodName());
        writeField("inp", !isLibraryFrame(stacktrace.getClassName()));
        writeField("ln", stacktrace.getLineNumber());
        serializeStackFrameModule(stacktrace.getClassName());
        jw.writeByte(OBJECT_END);
    }

    private void serializeStackFrameModule(final String fullyQualifiedClassName) {
        writeFieldName("p"); // "module"
        replaceBuilder.setLength(0);
        final int lastDotIndex = fullyQualifiedClassName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            replaceBuilder.append(fullyQualifiedClassName, 0, lastDotIndex);
        }
        writeStringBuilderValue(replaceBuilder, jw);
    }

    private boolean isLibraryFrame(String className) {
        for (String applicationPackage : stacktraceConfiguration.getApplicationPackages()) {
            if (className.startsWith(applicationPackage)) {
                return false;
            }
        }
        return true;
    }

    private void serializeSpanContext(SpanContext context, TraceContext traceContext) {
        writeFieldName("context");
        jw.writeByte(OBJECT_START);

        serializeServiceName(traceContext);
        serializeDbContext(context.getDb());
        serializeHttpContext(context.getHttp());

        // writeFieldName("tags");
        // serializeLabels(context);

        jw.writeByte(OBJECT_END);
        jw.writeByte(COMMA);
    }

    private void serializeDbContext(final Db db) {
        if (db.hasContent()) {
            writeFieldName("db");
            jw.writeByte(OBJECT_START);
            writeField("instance", db.getInstance());
            if (db.getStatement() != null) {
                writeLongStringField("statement", db.getStatement());
            } else {
                final CharBuffer statementBuffer = db.getStatementBuffer();
                if (statementBuffer != null && statementBuffer.length() > 0) {
                    writeFieldName("statement");
                    jw.writeString(statementBuffer);
                    jw.writeByte(COMMA);
                }
            }
            
            writeField("type", db.getType());
            writeField("link", db.getDbLink());
            writeLastField("user", db.getUser());
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeHttpContext(final Http http) {
        if (http.hasContent()) {
            writeFieldName("http");
            jw.writeByte(OBJECT_START);
            writeField("method", http.getMethod());
            int statusCode = http.getStatusCode();
            if (statusCode > 0) {
                writeField("status_code", http.getStatusCode());
            }
            writeLastField("url", http.getUrl());
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeSpanCount(final SpanCount spanCount) {
        writeFieldName("span_count");
        jw.writeByte(OBJECT_START);
        writeField("dropped", spanCount.getDropped().get());
        writeLastField("started", spanCount.getStarted().get());
        jw.writeByte(OBJECT_END);
        jw.writeByte(COMMA);
    }

    private void serializeContext(final TransactionContext context) {

    	if (context.getUser().hasContent()) {
            serializeUser(context.getUser());
            jw.writeByte(COMMA);
        }

        serializeRequestAndResponse(context.getRequest(), context.getResponse());
        if (context.hasCustom()) {
            writeFieldName("customData");
            serializeStringKeyScalarValueMap(context.getCustomIterator(), replaceBuilder, jw, true, true);
            jw.writeByte(COMMA);
        }

         // writeFieldName("tags");
         // serializeLabels(context);
         // jw.writeByte(COMMA);
    }

    private void serializeRequestAndResponse(final Request request, final Response response) {

        if (request.hasContent()) {
	        writeFieldName("request");
	        jw.writeByte(OBJECT_START);

	    	writeField("method", request.getMethod());
	    	writeField("statusCode", Integer.valueOf(response.getStatusCode()));

		    if (!request.getHeaders().isEmpty()) {
		    	if (request.getHeaders().get("accept") != null) {
		    		writeField("accept", request.getHeaders().get("accept").toString());
		    	}

		    	if (request.getHeaders().get("accept-encoding") != null) {
		    		writeField("accept-encoding", request.getHeaders().get("accept-encoding").toString());
		    	}

		    	if (request.getHeaders().get("accept-language") != null) {
		    		writeField("accept-language", request.getHeaders().get("accept-language").toString());
		    	}

		    	if (request.getHeaders().get("referer") != null) {
		    		writeField("referer", request.getHeaders().get("referer").toString());
		    	}

		    	if (request.getHeaders().get("user-agent") != null) {
		    		writeField("userAgent", request.getHeaders().get("user-agent").toString());
		    	}
		    }

	        if (request.getUrl().hasContent()) {
	            serializeUrl(request.getUrl());
	        }
	        if (request.getSocket().hasContent()) {
	            serializeSocket(request.getSocket());
	        }

	    	writeLastField("http-version", request.getHttpVersion());
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    // Not used
    private void serializeResponse(final Response response) {
        if (response.hasContent()) {
            writeFieldName("response");
            jw.writeByte(OBJECT_START);
            writeField("headers", response.getHeaders());
            writeField("finished", response.isFinished());
            writeField("headers_sent", response.isHeadersSent());
            writeFieldName("status_code");
            NumberConverter.serialize(response.getStatusCode(), jw);
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    // Not used
    private void serializeRequest(final Request request) {
        if (request.hasContent()) {
            writeFieldName("request");
            jw.writeByte(OBJECT_START);
            writeField("method", request.getMethod());
            writeField("headers", request.getHeaders());
            writeField("cookies", request.getCookies());

            // only one of those can be non-empty
            if (!request.getFormUrlEncodedParameters().isEmpty()) {
                writeField("body", request.getFormUrlEncodedParameters());
            } else if (request.getRawBody() != null) {
                writeField("body", request.getRawBody());
            } else {
                final CharBuffer bodyBuffer = request.getBodyBufferForSerialization();
                if (bodyBuffer != null && bodyBuffer.length() > 0) {
                    writeFieldName("body");
                    jw.writeString(bodyBuffer);
                    jw.writeByte(COMMA);
                }
            }
            if (request.getUrl().hasContent()) {
                serializeUrl(request.getUrl());
            }
            if (request.getSocket().hasContent()) {
                serializeSocket(request.getSocket());
            }
            writeLastField("http_version", request.getHttpVersion());
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializeUrl(final Url url) {
        writeField("url", url.getFull());
        writeField("host", url.getHostname());

        String portText = url.getPort().toString();
        int port = 0;
        try {
        	port = Integer.parseInt(portText);
        } catch (Exception e) {
        	// Do nothing.
        }
        writeField("port", port);

        writeField("path", url.getPathname());
        writeField("search", url.getSearch());
        // writeLastField("protocol", url.getProtocol());
    }

    private void serializeSocket(final Socket socket) {
        writeField("encrypted", socket.isEncrypted());
        writeField("ip", socket.getRemoteAddress());
    }

    // visible for testing
    void serializeLabels(AbstractContext context) {
        if (context.hasLabels()) {
            serializeStringKeyScalarValueMap(context.getLabelIterator(), replaceBuilder, jw, false, apmServerClient.supportsNonStringLabels());
        } else {
            jw.writeByte(OBJECT_START);
            jw.writeByte(OBJECT_END);
        }
    }

    private static void serializeStringKeyScalarValueMap(Iterator<? extends Map.Entry<String, ? /* String|Number|Boolean */>> it,
                                                         StringBuilder replaceBuilder, JsonWriter jw, boolean extendedStringLimit,
                                                         boolean supportsNonStringValues) {
        jw.writeByte(OBJECT_START);
        if (it.hasNext()) {
            Map.Entry<String, ?> kv = it.next();
            writeStringValue(sanitizeLabelKey(kv.getKey(), replaceBuilder), replaceBuilder, jw);
            jw.writeByte(JsonWriter.SEMI);
            serializeScalarValue(replaceBuilder, jw, kv.getValue(), extendedStringLimit, supportsNonStringValues);
            while (it.hasNext()) {
                jw.writeByte(COMMA);
                kv = it.next();
                writeStringValue(sanitizeLabelKey(kv.getKey(), replaceBuilder), replaceBuilder, jw);
                jw.writeByte(JsonWriter.SEMI);
                serializeScalarValue(replaceBuilder, jw, kv.getValue(), extendedStringLimit, supportsNonStringValues);
            }
        }
        jw.writeByte(OBJECT_END);
    }

    static void serializeLabels(Labels labels, StringBuilder replaceBuilder, JsonWriter jw) {
        if (!labels.isEmpty()) {
            if (labels.getTransactionName() != null || labels.getTransactionType() != null) {
                writeFieldName("transaction", jw);
                jw.writeByte(OBJECT_START);
                writeField("name", labels.getTransactionName(), replaceBuilder, jw);
                writeLastField("type", labels.getTransactionType(), replaceBuilder, jw);
                jw.writeByte(OBJECT_END);
                jw.writeByte(COMMA);
            }

            if (labels.getSpanType() != null || labels.getSpanSubType() != null) {
                writeFieldName("span", jw);
                jw.writeByte(OBJECT_START);
                writeField("type", labels.getSpanType(), replaceBuilder, jw);
                writeLastField("subtype", labels.getSpanSubType(), replaceBuilder, jw);
                jw.writeByte(OBJECT_END);
                jw.writeByte(COMMA);
            }

            writeFieldName("tags", jw);
            jw.writeByte(OBJECT_START);
            serialize(labels, replaceBuilder, jw);
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private static void serialize(Labels labels, StringBuilder replaceBuilder, JsonWriter jw) {
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                jw.writeByte(COMMA);
            }
            writeStringValue(sanitizeLabelKey(labels.getKey(i), replaceBuilder), replaceBuilder, jw);
            jw.writeByte(JsonWriter.SEMI);
            serializeScalarValue(replaceBuilder, jw, labels.getValue(i), false, false);
        }
    }

    private static void serializeScalarValue(StringBuilder replaceBuilder, JsonWriter jw, Object value, boolean extendedStringLimit, boolean supportsNonStringValues) {
        if (value instanceof String) {
            if (extendedStringLimit) {
                writeLongStringValue((String) value, replaceBuilder, jw);
            } else {
                writeStringValue((String) value, replaceBuilder, jw);
            }
        } else if (value instanceof Number) {
            if (supportsNonStringValues) {
                NumberConverter.serialize(((Number) value).doubleValue(), jw);
            } else {
                jw.writeNull();
            }
        } else if (value instanceof Boolean) {
            if (supportsNonStringValues) {
                BoolConverter.serialize((Boolean) value, jw);
            } else {
                jw.writeNull();
            }
        } else {
            // can't happen, as AbstractContext enforces the values to be either String, Number or boolean
            jw.writeString("invalid value");
        }
    }

    private static CharSequence sanitizeLabelKey(String key, StringBuilder replaceBuilder) {
        for (int i = 0; i < DISALLOWED_IN_LABEL_KEY.length; i++) {
            if (key.contains(DISALLOWED_IN_LABEL_KEY[i])) {
                return replaceAll(key, DISALLOWED_IN_LABEL_KEY, "_", replaceBuilder);
            }
        }
        return key;
    }

    private static CharSequence replaceAll(String s, String[] stringsToReplace, String replacement, StringBuilder replaceBuilder) {
        // uses a instance variable StringBuilder to avoid allocations
        replaceBuilder.setLength(0);
        replaceBuilder.append(s);
        for (String toReplace : stringsToReplace) {
            replace(replaceBuilder, toReplace, replacement, 0);
        }
        return replaceBuilder;
    }

    static void replace(StringBuilder replaceBuilder, String toReplace, String replacement, int fromIndex) {
        for (int i = replaceBuilder.indexOf(toReplace, fromIndex); i != -1; i = replaceBuilder.indexOf(toReplace, fromIndex)) {
            replaceBuilder.replace(i, i + toReplace.length(), replacement);
            fromIndex = i;
        }
    }

    private void writeField(final String fieldName, final PotentiallyMultiValuedMap map) {
        if (map.size() > 0) {
            writeFieldName(fieldName);
            jw.writeByte(OBJECT_START);
            final int size = map.size();
            if (size > 0) {
                serializePotentiallyMultiValuedEntry(map.getKey(0), map.getValue(0));
                for (int i = 1; i < size; i++) {
                    jw.writeByte(COMMA);
                    serializePotentiallyMultiValuedEntry(map.getKey(i), map.getValue(i));
                }
            }
            jw.writeByte(OBJECT_END);
            jw.writeByte(COMMA);
        }
    }

    private void serializePotentiallyMultiValuedEntry(String key, @Nullable Object value) {
        jw.writeString(key);
        jw.writeByte(JsonWriter.SEMI);
        if (value instanceof String) {
            StringConverter.serialize((String) value, jw);
        } else if (value instanceof List) {
            jw.writeByte(ARRAY_START);
            final List<String> values = (List<String>) value;
            jw.writeString(values.get(0));
            for (int i = 1; i < values.size(); i++) {
                jw.writeByte(COMMA);
                jw.writeString(values.get(i));
            }
            jw.writeByte(ARRAY_END);
        } else if (value == null) {
            jw.writeNull();
        }
    }

    private void serializeUser(final User user) {
        writeFieldName("user");
        jw.writeByte(OBJECT_START);
        writeField("id", user.getId());
        writeField("email", user.getEmail());
        writeLastField("username", user.getUsername());
        jw.writeByte(OBJECT_END);
    }

    void writeField(final String fieldName, final StringBuilder value) {
        if (value.length() > 0) {
            writeFieldName(fieldName);
            writeStringBuilderValue(value);
            jw.writeByte(COMMA);
        }
    }


    void writeLongStringField(final String fieldName, @Nullable final String value) {
        if (value != null) {
            writeFieldName(fieldName);
            writeLongStringValue(value);
            jw.writeByte(COMMA);
        }
    }

	void writeLongStringLastField(final String fieldName, @Nullable final String value) {
        if (value != null) {
            writeFieldName(fieldName);
            writeLongStringValue(value);
        }
    }

    void writeField(final String fieldName, @Nullable final String value) {
        writeField(fieldName, value, replaceBuilder, jw);
    }

    static void writeField(final String fieldName, @Nullable final CharSequence value, StringBuilder replaceBuilder, JsonWriter jw) {
        if (value != null) {
            writeFieldName(fieldName, jw);
            writeStringValue(value, replaceBuilder, jw);
            jw.writeByte(COMMA);
        }
    }

    private void writeStringBuilderValue(StringBuilder value) {
        writeStringBuilderValue(value, jw);
    }

    private static void writeStringBuilderValue(StringBuilder value, JsonWriter jw) {
        if (value.length() > MAX_VALUE_LENGTH) {
            value.setLength(MAX_VALUE_LENGTH - 1);
            value.append('…');
        }
        jw.writeString(value);
    }

    private void writeStringValue(CharSequence value) {
        writeStringValue(value, replaceBuilder, jw);
    }

    private static void writeStringValue(CharSequence value, StringBuilder replaceBuilder, JsonWriter jw) {
        if (value.length() > MAX_VALUE_LENGTH) {
            replaceBuilder.setLength(0);
            replaceBuilder.append(value, 0, Math.min(value.length(), MAX_VALUE_LENGTH + 1));
            writeStringBuilderValue(replaceBuilder, jw);
        } else {
            jw.writeString(value);
        }
    }

    private static void writeLongStringBuilderValue(StringBuilder value, JsonWriter jw) {
        if (value.length() > MAX_LONG_STRING_VALUE_LENGTH) {
            value.setLength(MAX_LONG_STRING_VALUE_LENGTH - 1);
            value.append('…');
        }
        jw.writeString(value);
    }

    private void writeLongStringValue(String value) {
        writeLongStringValue(value, replaceBuilder, jw);
    }

    private static void writeLongStringValue(String value, StringBuilder replaceBuilder, JsonWriter jw) {
        if (value.length() > MAX_LONG_STRING_VALUE_LENGTH) {
            replaceBuilder.setLength(0);
            replaceBuilder.append(value, 0, Math.min(value.length(), MAX_LONG_STRING_VALUE_LENGTH + 1));
            writeLongStringBuilderValue(replaceBuilder, jw);
        } else {
            jw.writeString(value);
        }
    }

    private void writeField(final String fieldName, final long value) {
        writeFieldName(fieldName);
        NumberConverter.serialize(value, jw);
        jw.writeByte(COMMA);
    }

    private void writeField(final String fieldName, final int value) {
        writeFieldName(fieldName);
        NumberConverter.serialize(value, jw);
        jw.writeByte(COMMA);
    }

    private void writeLastField(final String fieldName, final int value) {
        writeFieldName(fieldName);
        NumberConverter.serialize(value, jw);
    }

    private void writeField(final String fieldName, final boolean value) {
        writeFieldName(fieldName);
        BoolConverter.serialize(value, jw);
        jw.writeByte(COMMA);
    }

    private void writeLastField(final String fieldName, final boolean value) {
        writeFieldName(fieldName);
        BoolConverter.serialize(value, jw);
    }

    private void writeField(final String fieldName, final double value) {
        writeFieldName(fieldName);
        NumberConverter.serialize(value, jw);
        jw.writeByte(COMMA);
    }

    void writeLastField(final String fieldName, @Nullable final String value) {
        writeLastField(fieldName, value, replaceBuilder, jw);
    }

    static void writeLastField(final String fieldName, @Nullable final String value, StringBuilder replaceBuilder, final JsonWriter jw) {
        writeFieldName(fieldName, jw);
        if (value != null) {
            writeStringValue(value, replaceBuilder, jw);
        } else {
            jw.writeNull();
        }
    }

    public static void writeFieldName(final String fieldName, final JsonWriter jw) {
        jw.writeByte(JsonWriter.QUOTE);
        jw.writeAscii(fieldName);
        jw.writeByte(JsonWriter.QUOTE);
        jw.writeByte(JsonWriter.SEMI);
    }

    private void writeFieldName(final String fieldName) {
        writeFieldName(fieldName, jw);
    }

    private void writeField(final String fieldName, final List<String> values) {
        if (values.size() > 0) {
            writeFieldName(fieldName);
            jw.writeByte(ARRAY_START);
            jw.writeString(values.get(0));
            for (int i = 1; i < values.size(); i++) {
                jw.writeByte(COMMA);
                jw.writeString(values.get(i));
            }
            jw.writeByte(ARRAY_END);
            jw.writeByte(COMMA);
        }
    }

    private void writeHexField(String fieldName, Id traceId) {
        writeFieldName(fieldName);
        jw.writeByte(JsonWriter.QUOTE);
        traceId.writeAsHex(jw);
        jw.writeByte(JsonWriter.QUOTE);
        jw.writeByte(COMMA);
    }

    private void writeTimestamp(final long epochMicros) {
        writeFieldName("timestamp");
        NumberConverter.serialize(epochMicros / TimeUnit.MILLISECONDS.toMicros(1), jw);
        jw.writeByte(COMMA);
    }
}
