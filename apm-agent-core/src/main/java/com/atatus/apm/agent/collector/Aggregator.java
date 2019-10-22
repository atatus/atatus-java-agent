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
package com.atatus.apm.agent.collector;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atatus.apm.agent.collector.payload.ErrorMetricPayload;
import com.atatus.apm.agent.collector.payload.TracePayload;
import com.atatus.apm.agent.collector.payload.TransactionPayload;
import com.atatus.apm.agent.collector.payload.Types;
import com.atatus.apm.agent.collector.serialize.JsonSerializer;
import com.atatus.apm.agent.collector.util.BlockingException;
import com.atatus.apm.agent.impl.MetaData;
import com.atatus.apm.agent.impl.error.ErrorCapture;
import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.impl.transaction.Transaction;
import com.atatus.apm.agent.metrics.MetricRegistry;
import com.atatus.apm.agent.report.ApmServerClient;
import com.atatus.apm.agent.report.ApmServerReporter;
import com.atatus.apm.agent.report.ReporterConfiguration;
import com.atatus.apm.agent.report.ReportingEvent;
import com.atatus.apm.agent.report.ReportingEventHandler;
import com.atatus.apm.agent.report.processor.ProcessorEventHandler;
import com.atatus.apm.agent.report.serialize.PayloadSerializer;

public class Aggregator implements ReportingEventHandler, Runnable {

	/** Maximum number of errors and metrics kept in memory */
	static final int DEFAULT_QUEUE_SIZE = 20;
	/** Maximum number of traces kept in memory */
	static final int TRACE_DEFAULT_QUEUE_SIZE = 10;
	/** Flush interval for the API in seconds */
	static final long FLUSH_TIME_SECONDS = 60;
	/** Maximum amount of time to await for scheduler to shutdown */
	static final long SHUTDOWN_TIMEOUT_SECONDS = 10;
	/** Maximum response time in milliseconds to be reported as trace */
	static final long TRACE_THRESHOLD_MS = 2000;

	private static final Logger logger = LoggerFactory.getLogger(Aggregator.class);

	private HashMap<String, TransactionPayload> transactionPayloadMap;
	private HashMap<String, ArrayList<Span>> spanMap;

	private final PriorityBlockingQueue<TracePayload> tracePayloadQueue;
	private final BlockingQueue<ErrorCapture> errorQueue;
	private final BlockingQueue<MetricRegistry> metricsQueue;
	private ErrorMetricPayload errorMetricPayload;

    private final ReporterConfiguration reporterConfiguration;
    private final ProcessorEventHandler processorEventHandler;
    private final MetaData metaData;
    private final JsonSerializer payloadSerializer;
    private final ApmServerClient apmServerClient;
	private final Transporter transporter;

    @Nullable
    private ApmServerReporter reporter;
    private volatile boolean shutDown;

	/** Scheduled thread pool, acting like a cron */
	private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1, THREAD_FACTORY);
	private final ShutdownCallback shutdownCallback;

	public Aggregator(ReporterConfiguration reporterConfiguration, ProcessorEventHandler processorEventHandler,
            PayloadSerializer payloadSerializer, MetaData metaData, ApmServerClient apmServerClient) {

		this.reporterConfiguration = reporterConfiguration;
		this.processorEventHandler = processorEventHandler;
		this.payloadSerializer = (JsonSerializer) payloadSerializer;
		this.metaData = metaData;
		this.apmServerClient = apmServerClient;

		this.transporter = new Transporter();
		this.errorMetricPayload = new ErrorMetricPayload();

		this.transactionPayloadMap = new HashMap<String, TransactionPayload>();
		this.spanMap = new HashMap<String, ArrayList<Span>>();
		this.tracePayloadQueue = new PriorityBlockingQueue<>(TRACE_DEFAULT_QUEUE_SIZE, new TracePayloadComparator());
		this.errorQueue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE);
		this.metricsQueue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE);

		shutdownCallback = new ShutdownCallback(executorService);
		Types.load();

		// Check license key
		String licenseKey = reporterConfiguration.getLicenseKey();
		String appName = reporterConfiguration.getAppName();
		if ((licenseKey == null || licenseKey == "") && (appName == null || appName == "")) {
			logger.error("License key and App name is missing!");
			shutDown = true;
			return;
		}
		if (licenseKey == null || licenseKey == "") {
			logger.error("License key is missing!");
			shutDown = true;
			return;
		}
		if (appName == null || appName == "") {
			logger.error("App Name is missing!");
			shutDown = true;
			return;
		}

		this.start();

		// Send host info
		try {
			transporter.send(this.payloadSerializer.toJsonHostInfo(reporterConfiguration,
					metaData), Transporter.HOST_INFO_PATH);
		} catch (Exception e) {
			// No need to shutdown during initial host info.
		}
	}

	private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
		@Override
		public Thread newThread(final Runnable r) {
			final Thread thread = new Thread(r, "atatus-agent-reporter");
			thread.setDaemon(true);
			return thread;
		}
	};

	public void start() {
		executorService.scheduleAtFixedRate(this, 0, FLUSH_TIME_SECONDS, TimeUnit.SECONDS);
		try {
			Runtime.getRuntime().addShutdownHook(shutdownCallback);
		} catch (final IllegalStateException ex) {
			// The JVM is already shutting down.
		}
	}


    @Override
    public void onEvent(ReportingEvent event, long sequence, boolean endOfBatch) {
        if (logger.isDebugEnabled()) {
            logger.debug("Receiving {} event (sequence {})", event.getType(), sequence);
        }
        // logger.info("Atatus Debug: Receiving {} event (sequence {})", event.getType(), sequence);
        try {
            if (!shutDown) {
				try {
					handleEvent(event, sequence, endOfBatch);
				} catch(Exception e) {
					logger.info("Exception e {}", e);
				}
            }
        } finally {
            event.resetState();
        }
    }

    private void handleEvent(ReportingEvent event, long sequence, boolean endOfBatch) {
        if (event.getType() == null) {
            return;
        } else if (event.getType() == ReportingEvent.ReportingEventType.FLUSH) {
            flush();
            return;
        } else if (event.getType() == ReportingEvent.ReportingEventType.SHUTDOWN) {
            shutDown = true;
            flush();
            return;
        }

        processorEventHandler.onEvent(event, sequence, endOfBatch);
        writeEvent(event);
        if (shouldFlush()) {
            flush();
        }
    }

    private void writeEvent(ReportingEvent event) {

        if (event.getTransaction() != null) {

			Transaction transaction = event.getTransaction();
			String transactionName = transaction.getNameAsString();
			String transactionId = transaction.getTraceContext().getTransactionId().toString();

	        String frameworkName = null;
	        if (metaData.getService().getFramework() != null) {
	        	frameworkName = metaData.getService().getFramework().getName();
	        } else {
	        	frameworkName = Types.TYPE_JAVA;
	        }

	        ArrayList<Span> spanList = spanMap.get(transactionId);

			if (transaction.getDurationMs() >= TRACE_THRESHOLD_MS &&
					spanList != null && spanList.size() > 0) {
		        TracePayload tracePayload = new TracePayload(transaction, spanList, frameworkName);

	    		if (!this.tracePayloadQueue.offer(tracePayload)) {
	    			logger.debug("Trace Payload queue is full, dropping trace payload {}", tracePayload.getName());
	    		} else {
	    			// Remove top head element in queue size is more than default queue size
	    			// This is because priority queue does not limit to max size.
	    			if (this.tracePayloadQueue.size() > TRACE_DEFAULT_QUEUE_SIZE) {
	    				this.tracePayloadQueue.poll();
	    			}
	    		}
		    }

			TransactionPayload transactionPayload = transactionPayloadMap.get(transactionName);
			if (transactionPayload != null) {
				transactionPayload.aggregate(transaction, spanList);
			} else {
				transactionPayload = new TransactionPayload(transaction, frameworkName);
				transactionPayload.aggregate(transaction, spanList);
				transactionPayloadMap.put(transactionName, transactionPayload);
			}

			if (transaction.getContext().getResponse().getStatusCode() >= 400) {
				errorMetricPayload.add(transaction, frameworkName);
			}

			spanMap.remove(transactionId);
            event.getTransaction().decrementReferences();

        } else if (event.getSpan() != null) {

        	Span span = event.getSpan();
        	String transactionId = span.getTraceContext().getTransactionId().toString();

        	ArrayList<Span> spans = spanMap.get(transactionId);
        	if (spans == null) {
        		spans = new ArrayList<Span>();
        		spans.add(span);
            	spanMap.put(transactionId, spans);
        	} else {
        		spans.add(span);
        	}

            // event.getSpan().decrementReferences();

        } else if (event.getError() != null) {

    		if (!this.errorQueue.offer(event.getError())) {
    			logger.debug("Error queue is full, dropping error {}", event.getError());
    			event.getError().recycle();
    		}

        } else if (event.getMetricRegistry() != null) {

    		if (!this.metricsQueue.offer(event.getMetricRegistry())) {
    			logger.debug("MetricRegistry queue is full, dropping metrics {}", event.getMetricRegistry());
    		}

        } else {
        	logger.debug("Unknown metric {}", event.getType());
        }
    }


	@Override
	public void init(ApmServerReporter reporter) {
		// FIXME: Need to remove APM Server reporter.
		this.reporter = reporter;
	}

    @Override
    public long getReported() {
        return 0;
    }

    @Override
    public long getDropped() {
        return 0;
    }

    @Override
    public void close() {
        shutDown = true;
		shutdownCallback.run();
    }

    private boolean shouldFlush() {
    	return false;
        // final long written = deflater.getBytesWritten() + DslJsonSerializer.BUFFER_SIZE;
        // final boolean flush = written >= reporterConfiguration.getApiRequestSize();
        // if (flush && logger.isDebugEnabled()) {
        //    logger.debug("Flushing, because request size limit exceeded {}/{}", written, reporterConfiguration.getApiRequestSize());
        // }
        // return flush;
    }

    void flush() {
    	this.run();
    }

	@Override
	public void run() {

		try {

			// Send every one 30 minutes
			int currentMinute = Calendar.getInstance().get(Calendar.MINUTE);
			if (currentMinute == 0 || currentMinute == 30) {
				transporter.send(payloadSerializer.toJsonHostInfo(reporterConfiguration,
						metaData), Transporter.HOST_INFO_PATH);
			}

			if (shutDown) {
				return;
			}

			// logger.info("Atatus Debug: Processing Transaction: {}, Trace: {}, ErrorMetric: {}, Errors: {}, Metrics: {}",
			//		this.transactionPayloadMap.size(), this.tracePayloadQueue.size(), this.errorMetricPayload.size(),
			//		this.errorQueue.size(), this.metricsQueue.size());

			
			if (!this.transactionPayloadMap.isEmpty()) {
				HashMap<String, TransactionPayload> transactionPayloadMapToWrite = this.transactionPayloadMap;
				this.transactionPayloadMap = new HashMap<String, TransactionPayload>();
				transporter.send(payloadSerializer.toJsonTransactions(transactionPayloadMapToWrite,
							reporterConfiguration, metaData), Transporter.TRANSACTION_PATH);
			}

			if (!this.tracePayloadQueue.isEmpty()) {
				PriorityBlockingQueue<TracePayload> tracePayloadQueueToWrite = new PriorityBlockingQueue<>(TRACE_DEFAULT_QUEUE_SIZE, new TracePayloadComparator());
				this.tracePayloadQueue.drainTo(tracePayloadQueueToWrite);
				transporter.send(payloadSerializer.toJsonTraces(tracePayloadQueueToWrite,
						reporterConfiguration, metaData), Transporter.TRACE_PATH);
			}

			if (!this.errorMetricPayload.isEmpty()) {
				ErrorMetricPayload errorMetricPayloadToWrite = this.errorMetricPayload;
				this.errorMetricPayload = new ErrorMetricPayload();
				transporter.send(payloadSerializer.toJsonErrorMetrics(errorMetricPayloadToWrite,
						reporterConfiguration, metaData), Transporter.ERROR_METRIC_PATH);
			}

			if (!this.errorQueue.isEmpty()) {
				BlockingQueue<ErrorCapture> errorQueueToWrite = new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE);
				this.errorQueue.drainTo(errorQueueToWrite);
				transporter.send(payloadSerializer.toJsonErrors(errorQueueToWrite,
						reporterConfiguration, metaData), Transporter.ERROR_PATH);
			}

			if (!this.metricsQueue.isEmpty()) {
				BlockingQueue<MetricRegistry> metricsQueueToWrite = new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE);
				this.metricsQueue.drainTo(metricsQueueToWrite);
				transporter.send(payloadSerializer.toJsonMetrics(metricsQueueToWrite,
		        		reporterConfiguration, metaData), Transporter.METRIC_PATH);

			}

		} catch (final BlockingException e) {
			logger.debug("Failed to send payload to the Atatus: {}", e.getMessage());
			// logger.info("Atatus Debug: Failed to send payload to the Atatus: {}", e);

			// Reset all queues and map
			this.transactionPayloadMap = new HashMap<String, TransactionPayload>();
			this.errorMetricPayload = new ErrorMetricPayload();

			this.tracePayloadQueue.clear();
			this.errorQueue.clear();
			this.metricsQueue.clear();

	        shutDown = true;
		}

	}

	/**
	 * Helper to handle shutting down of the Writer because JVM is shutting down or
	 * Writer is closed.
	 */
	// Visible for testing
	static final class ShutdownCallback extends Thread {

		private final ExecutorService executorService;

		public ShutdownCallback(final ExecutorService executorService) {
			this.executorService = executorService;
		}

		@Override
		public void run() {
			// We use this logic in two cases:
			// * When JVM is shutting down
			// * When Writer is closed manually/via GC
			// In latter case we need to remove shutdown hook.
			try {
				Runtime.getRuntime().removeShutdownHook(this);
			} catch (final IllegalStateException ex) {
				// The JVM may be shutting down.
			}

			try {
				executorService.shutdownNow();
				executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (final InterruptedException e) {
				logger.info("Writer properly closed and async writer interrupted.");
			}
		}
	}

	// Trace payload comparator for ascending order
	class TracePayloadComparator implements Comparator<TracePayload>{

        public int compare(TracePayload t1, TracePayload t2) {

            if (t1.getDurationMs() > t2.getDurationMs()) {
                return 1;
            } else if (t1.getDurationMs() < t2.getDurationMs()) {
                return -1;
        	}

            return 0;
        }
    }

}
