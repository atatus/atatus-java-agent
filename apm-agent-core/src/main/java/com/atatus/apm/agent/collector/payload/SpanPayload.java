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


import com.atatus.apm.agent.impl.transaction.Span;

/**
 * Data captured by an agent representing an event occurring in a monitored
 * service
 */
public class SpanPayload extends Payload {

	private final String name;
	private final String kind;
	private final String type;
	private final Long[] durations = new Long[4];

	public SpanPayload(Span span) {
		this.name = span.getNameAsString();
		this.kind = Types.getCleanType(span.getType());
		this.type = Types.getCleanSubType(span.getSubtype());

		long duration = span.getDuration();

		this.durations[0] = 1l;
		this.durations[1] = duration;
		this.durations[2] = duration;
		this.durations[3] = duration;
	}

	public void aggregate(Span span) {
		long duration = span.getDuration();

		this.durations[0]++;
		this.durations[1] += duration;
		this.durations[2] = Math.min(duration, this.durations[2]);
		this.durations[3] = Math.max(duration, this.durations[3]);
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

	public Long[] getDurations() {
		return durations;
	}

}
