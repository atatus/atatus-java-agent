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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.impl.transaction.Transaction;

/**
 * Data captured by an agent representing an event occurring in a monitored
 * service
 */
public class TransactionPayload extends Payload {

	private final String kind = Types.TYPE_JAVA;

	private final String name;
	private final String type;
	private final boolean background;
	private final Long[] durations;
	private final HashMap<String, SpanPayload> spanPayloadMap;
	private static final Logger logger = LoggerFactory.getLogger(TransactionPayload.class);

	public TransactionPayload(Transaction transaction, String frameworkName) {
		this.name = transaction.getNameAsString();
		this.type = frameworkName;
		this.background = (transaction.getType() == "backgroundjob");
		this.durations = new Long[]{0l, 0l, 0l, 0l};
		this.spanPayloadMap = new HashMap<String, SpanPayload>();
	}

	public void aggregate(Transaction transaction, List<Span> spans) {

		long duration = transaction.getDuration();

		if (this.durations[0] == 0l) {
			this.durations[0] = 1l;
			this.durations[1] = duration;
			this.durations[2] = duration;
			this.durations[3] = duration;
		} else {
			this.durations[0]++;
			this.durations[1] += duration;
			this.durations[2] = Math.min(duration, this.durations[2]);
			this.durations[3] = Math.max(duration, this.durations[3]);
		}

		if (spans != null) {
			for (int i = 0; i < spans.size(); i++) {
				Span span = spans.get(i);
				String spanName = span.getNameAsString();
				// logger.info("Span name {}", spanName);
				// logger.info("Span duration {}", span.getDuration());

				SpanPayload spanPayload = spanPayloadMap.get(spanName);
				if (spanPayload != null) {
					spanPayload.aggregate(span);
				} else {
					spanPayload = new SpanPayload(span);
					spanPayloadMap.put(spanName, spanPayload);
				}

				span.decrementReferences();
			}
		}
	}

	public String getName() {
		return name;
	}

	public String getKind() {
		return kind;
	}

	public String getType() {
		return type;
	}

	public boolean isBackground() {
		return background;
	}

	public Long[] getDurations() {
		return durations;
	}

	public HashMap<String, SpanPayload> getSpanPayloadMap() {
		return spanPayloadMap;
	}

}


