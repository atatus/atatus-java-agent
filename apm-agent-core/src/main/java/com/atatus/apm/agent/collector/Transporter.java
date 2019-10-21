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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.MapConverter;

import com.atatus.apm.agent.collector.util.BlockingException;
import com.atatus.apm.agent.report.HttpUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Transporter {

	// static final String APM_ENDPOINT = "https://apm-rx.atatus.com/track/apm/";
	static final String APM_ENDPOINT = "http://127.0.0.1:8091/track/apm/";
	public static final String HOST_INFO_PATH = "hostinfo";
	public static final String ERROR_PATH = "error";
	public static final String ERROR_METRIC_PATH = "error_metric";
	public static final String TRANSACTION_PATH = "txn";
	public static final String TRACE_PATH = "trace";
	public static final String METRIC_PATH = "metric";

	static final String CONTENT_TYPE = "Content-Type";
	static final String APPLICATION_JSON = "application/json";
	static final int CONNECT_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(1);
	static final int READ_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(1);

    private final DslJson<Object> dslJson = new DslJson<>(new DslJson.Settings<>());

	private static final Logger logger = LoggerFactory.getLogger(Transporter.class);


	public Transporter() {
	}

	/**
	 * Send to the Atatus backend
	 *
	 * @param payload    the payload to be sent
	 * @param traceCount total number of traces
	 * @throws BlockingException
	 */
	public void send(final String payload, String path) throws BlockingException {
		try {
			// logger.info("Atatus Debug: Sending to {} {}", APM_ENDPOINT, path);
			// logger.info("Atatus Debug: Payload: {}", payload);
			final HttpURLConnection connection = createHttpConnection(path);

			// Serialize payload into string and write into output stream.
			OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
			writer.write(payload);
			writer.close();

			final int responseCode = connection.getResponseCode();
			if (responseCode != 200) {
				String error = String.format(
						"Error sending payload to the Atatus agent. Status: %d, ResponseMessage: %s",
						responseCode, connection.getResponseMessage());
				logger.error(error);

				if (responseCode == 400) {
					AtatusResponse atatusResponse = parseResponse(connection.getInputStream());
					if (atatusResponse.isBlocked()) {
						throw new BlockingException(error);
					}
				}
			}

		} catch (final BlockingException e) {
			throw e;
		} catch (final Exception e) {
			e.printStackTrace();
			logger.warn("Error while sending {} of {} payload to the Atatus agent.", e);
		}
	}

	private AtatusResponse parseResponse(InputStream in) {
		try {
            // prints out the version info of the APM Server
            String body = HttpUtils.readToString(in);
            // logger.info("Atatus Debug: Response Body: {}", body);
            JsonReader<Object> reader = dslJson.newReader(body.getBytes(UTF_8));
            reader.startObject();

            Map<String, String> map = MapConverter.deserialize(reader);
            String errorCode = map.get("errorCode");
            String errorMessage = map.get("errorMessage");
            String blockedString = map.get("blocked");

            // logger.info("Atatus Debug: Response Body blockedString: {}", blockedString);

            return new AtatusResponse(errorCode, errorMessage, blockedString);

        } catch (Exception e) {
        	logger.warn("Error while parsing response from Atatus agent.", e);
        }

		return new AtatusResponse(null, null, null);
	}

	private HttpURLConnection createHttpConnection(String path) throws IOException {
		URL agentUrl;
		try {
			agentUrl = new URL(APM_ENDPOINT + path);
		} catch (final MalformedURLException e) {
			// This should essentially mean agent should bail out from installing, we cannot
			// meaningfully
			// recover from this.
			throw new RuntimeException("Cannot parse agent url: " + APM_ENDPOINT, e);
		}

		final HttpURLConnection connection = (HttpURLConnection) agentUrl.openConnection();
		connection.setDoOutput(true);
		connection.setDoInput(true);

		// It is important to have timeout for agent request here: we need to finish
		// request in some reasonable amount
		// of time to allow following requests to be run.
		connection.setConnectTimeout(CONNECT_TIMEOUT);
		connection.setReadTimeout(READ_TIMEOUT);

		connection.setRequestMethod("POST");
		connection.setRequestProperty(CONTENT_TYPE, APPLICATION_JSON);

		return connection;
	}

}


class AtatusResponse {

	private final String errorCode;
	private final String errorMessage;
	private final boolean isBlocked;

	public AtatusResponse(String errorCode, String errorMessage, String blockedString) {
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
		this.isBlocked = (blockedString == "true");
	}

	public String getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public boolean isBlocked() {
		return isBlocked;
	}

}