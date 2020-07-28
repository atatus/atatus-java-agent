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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atatus.apm.agent.collector.Aggregator;
import com.atatus.apm.agent.impl.context.TransactionContext;
import com.atatus.apm.agent.impl.transaction.Db;
import com.atatus.apm.agent.impl.transaction.Http;
import com.atatus.apm.agent.impl.transaction.Span;
import com.atatus.apm.agent.impl.transaction.Transaction;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.ObjectConverter;


/**
 * @author apple
 *
 */
public class TracePayload extends Payload {

	private final String name;
	private final String kind = Types.KIND_TRANSACTION;
	private final String type;
	private final double durationMs;
	private final TransactionContext context;
	private final ArrayList<TraceSpanPayload> traceSpans;
	private final ArrayList<String> functions;
	private final RequestPayload requestPayload;

	private static final Logger logger = LoggerFactory.getLogger(Aggregator.class);

	public TracePayload(final Transaction transaction, ArrayList<Span> spans, String frameworkName) {
		this.type = frameworkName;
		this.name = transaction.getNameAsString();
		this.context = transaction.getContext();
		this.durationMs = transaction.getDurationMs();

		requestPayload = new RequestPayload(transaction.getContext().getRequest(),
								transaction.getContext().getResponse());
		traceSpans = new ArrayList<TraceSpanPayload>();
		functions = new ArrayList<String>();
		addSpans(spans, transaction);
	}

	private void addSpans(final ArrayList<Span> spans, final Transaction transaction) {
		if (spans == null) {
			return;
		}

		for (int i = 0; i < spans.size(); i++) {
			Span span = spans.get(i);

			TraceSpanPayload traceSpan = new TraceSpanPayload();
			traceSpan.setIndex(i);
			traceSpan.setLevel(1);
			traceSpan.setStartOffset((span.getTimestamp() - transaction.getTimestamp()) / TimeUnit.MILLISECONDS.toMicros(1));
			traceSpan.setDurationMs(span.getDurationMs());
			traceSpan.setName(span.getNameAsString());
			traceSpan.setKind(Types.getCleanType(span.getType()));
			traceSpan.setType(Types.getCleanSubType(span.getSubtype()));

			this.functions.add(span.getNameAsString());
			this.traceSpans.add(traceSpan);

			getDbContext(traceSpan.getType(), traceSpan.getName(), span.getContext().getDb(), traceSpan);
			getHttpContext(span.getContext().getHttp(), traceSpan);
		}
	}

    private void getDbContext(final String dbType, final String spanName, final Db db, final TraceSpanPayload traceSpan) {
        if (db.hasContent()) {
        	traceSpan.setInstance(db.getInstance());

        	String statement = db.getStatement();
            if (statement == null) {
                final CharBuffer statementBuffer = db.getStatementBuffer();
                if (statementBuffer != null && statementBuffer.length() > 0) {
                	statement = statementBuffer.toString();
                }
            }

            if (statement != null && !statement.trim().isEmpty()) {
	        	if (dbType.equalsIgnoreCase("MongoDB")) {
	        		traceSpan.setStatement(spanName + " " + this.obfuscateMongoDBStatement(statement));
	        	} else {
	        		traceSpan.setStatement(statement);
	        	}
            }

        	// traceSpan.setUser(db.getUser());
            // traceSpan.setType(db.getType());
            // traceSpan.setDBLink(db.getDbLink());

			//  logger.info("Atatus Debug:  DB Type {}", db.getType());
			//  logger.info("Atatus Debug:  DB Link {}", db.getDbLink());
			//  logger.info("Atatus Debug: DB User {}", db.getUser());
        }
    }

    private void getHttpContext(final Http http, final TraceSpanPayload traceSpan) {
        if (http.hasContent()) {
        	traceSpan.setMethod(http.getMethod());
        	int statusCode = http.getStatusCode();
            if (statusCode > 0) {
            	traceSpan.setStatusCode(statusCode);
            }
            traceSpan.setUrl(http.getUrl());
        }
    }

    private final DslJson<Object> dslJson = new DslJson<>(new DslJson.Settings<>());
	private static final String QUESTION_MARK = "?";
    private final String obfuscateMongoDBStatement(String statement) {

    	try {
    		// statement = "{\"$project\":{\"multiply\":{\"$multiply\":[\"$rate\",\"$hrs\",52]},\"rate\":1,\"class\":1}}";
    		// statement = "{\"$project\":{\"multiply\":{\"$multiply\":[\"$rate\",\"$hrs\",52]},\"rate\":1,\"class\":1},\"aggregate\":[{\"$match\":{\"$or\":[{\"class\":\"a\"},{\"$and\":[{\"class\":\"b\"},{\"hrs\":{\"$exists\":1}}]}]}},{\"$project\":{\"rateMultiply\":{\"$multiply\":[\"$rate\",\"$hrs\",52]},\"rate\":1,\"class\":1,\"hrs\":1}},{\"$match\":{\"$or\":[{\"$and\":[{\"class\":\"a\"},{\"rate\":{\"$gt\":20000}}]},{\"$and\":[{\"class\":\"b\"},{\"rateMultiply\":{\"$gt\":20000}}]}]}},{\"$project\":{\"class\":1,\"rate\":1,\"hrs\":1}}]}";

	        JsonReader<Object> reader = dslJson.newReader(statement.getBytes(UTF_8));
	        reader.startObject();

	        LinkedHashMap<String, Object> map = ObjectConverter.deserializeMap(reader);
	        this.obfuscateValue(map);

	        JsonWriter newWriter = dslJson.newWriter(16384);
	        ObjectConverter.serializeObject(map, newWriter);
	        String obfuscatedStatement = newWriter.toString();
	        newWriter.reset();

	        // logger.info("Atatus Debug: MongoDB Statement {}", statement);
	        // logger.info("Atatus Debug: MongoDB Obfuscation {}", obfuscatedStatement);

	        return obfuscatedStatement;

        } catch (Exception e) {
        	logger.warn("Error while obfuscating mongodb statement.", e);
        	logger.debug("Error: ", e);
        	return statement;
        }
    }

    private void obfuscateValue(LinkedHashMap<String, Object> map) {

        for(Map.Entry<String, Object> entry: map.entrySet()) {

            Object value = entry.getValue();
            if(value instanceof LinkedHashMap) {
            	obfuscateValue((LinkedHashMap<String, Object>) value);

            } else if(value instanceof ArrayList<?>) {

            	ArrayList<Object> array = (ArrayList<Object>) value;

                for(int i = 0; i < array.size(); i++) {
                	if(array.get(i) instanceof LinkedHashMap) {
                		obfuscateValue((LinkedHashMap<String, Object>) array.get(i));
                	} else {
                		array.set(i, QUESTION_MARK);
                	}
                }
            } else {
            	entry.setValue(QUESTION_MARK);
            }
        }
    }

	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}

	public String getKind() {
		return kind;
	}

	public double getDurationMs() {
		return durationMs;
	}

	public TransactionContext getContext() {
		return context;
	}

	public RequestPayload getRequestPayload() {
		return requestPayload;
	}

	public ArrayList<TraceSpanPayload> getTraceSpans() {
		return traceSpans;
	}

	public ArrayList<String> getFunctions() {
		return functions;
	}

}

