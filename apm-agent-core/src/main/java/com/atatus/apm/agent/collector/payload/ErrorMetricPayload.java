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
package com.atatus.apm.agent.collector.payload;

import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atatus.apm.agent.collector.Aggregator;
import com.atatus.apm.agent.impl.transaction.Transaction;

/**
 * Data captured by an agent representing an event occurring in a monitored
 * service
 */
public class ErrorMetricPayload extends Payload {

	static final int ERROR_REQUEST_DEFAULT_QUEUE_SIZE = 20;

	private final BlockingQueue<ErrorRequestPayload> errorRequestPayloadQueue;
	private final HashMap<String, ErrorMetricStatusCodePayload> errorMetricStatusCodePayloadMap;

	private static final Logger logger = LoggerFactory.getLogger(Aggregator.class);

	public ErrorMetricPayload() {
		errorRequestPayloadQueue = new ArrayBlockingQueue<>(ERROR_REQUEST_DEFAULT_QUEUE_SIZE);
		errorMetricStatusCodePayloadMap = new HashMap<String, ErrorMetricStatusCodePayload>();
	}

	public void add(Transaction transaction, String frameworkName) {

		String transactionName = transaction.getNameAsString();

		ErrorMetricStatusCodePayload errorMetricStatusCodePayload = errorMetricStatusCodePayloadMap.get(transactionName);
		if (errorMetricStatusCodePayload != null) {
			errorMetricStatusCodePayload.aggregate(transaction);
		} else {
			errorMetricStatusCodePayload = new ErrorMetricStatusCodePayload(transaction, frameworkName);
			errorMetricStatusCodePayloadMap.put(transactionName, errorMetricStatusCodePayload);
		}

		ErrorRequestPayload errorRequestPayload = new ErrorRequestPayload(transaction, frameworkName);
		if (!this.errorRequestPayloadQueue.offer(errorRequestPayload)) {
			logger.debug("Error Request queue is full, dropping error {}", transaction);
		}
	}

	public BlockingQueue<ErrorRequestPayload> getErrorRequestPayloadQueue() {
		return errorRequestPayloadQueue;
	}

	public HashMap<String, ErrorMetricStatusCodePayload> getErrorMetricStatusCodePayloadMap() {
		return errorMetricStatusCodePayloadMap;
	}

	public boolean isEmpty() {
		return errorMetricStatusCodePayloadMap.isEmpty();
	}

	public int size() {
		return errorMetricStatusCodePayloadMap.size();
	}
	
}
