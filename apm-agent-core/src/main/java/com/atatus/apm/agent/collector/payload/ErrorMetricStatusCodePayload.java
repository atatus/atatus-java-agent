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
import com.atatus.apm.agent.impl.context.TransactionContext;
import com.atatus.apm.agent.impl.transaction.Transaction;

/**
 * Data captured by an agent representing an event occurring in a monitored
 * service
 */
public class ErrorMetricStatusCodePayload extends Payload {

	private final String kind = Types.KIND_TRANSACTION;

	static final int ERROR_REQUEST_DEFAULT_QUEUE_SIZE = 20;
	private final String name;
	private final String type;

	private final HashMap<Integer, Integer> statusCodes = new HashMap<Integer, Integer>();

	public ErrorMetricStatusCodePayload(Transaction transaction, String frameworkName) {
		this.name = transaction.getNameAsString();
		this.type = frameworkName;
		this.aggregate(transaction);
	}

	public void aggregate(Transaction transaction) {
		int statusCode = transaction.getContext().getResponse().getStatusCode();
		Integer integer = this.statusCodes.get(statusCode);
		if (integer != null) {
			this.statusCodes.put(statusCode, integer + 1);
		} else {
			this.statusCodes.put(statusCode, 1);
		}
	}

	public String getKind() {
		return kind;
	}

	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}


	public HashMap<Integer, Integer> getStatusCodes() {
		return statusCodes;
	}

}
