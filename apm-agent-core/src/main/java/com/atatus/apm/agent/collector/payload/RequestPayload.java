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

import com.atatus.apm.agent.impl.context.Request;
import com.atatus.apm.agent.impl.context.Response;
import com.atatus.apm.agent.impl.context.Socket;
import com.atatus.apm.agent.impl.context.Url;

/**
 * Request payload
 * <p>
 *
 */
public class RequestPayload extends Payload  {

	HashMap<String, Object> requestMap = new HashMap<String, Object>();

    public RequestPayload(Request request, Response response) {

        requestMap.put("method", request.getMethod());
        requestMap.put("http-version", request.getHttpVersion());
        requestMap.put("statusCode", Integer.valueOf(response.getStatusCode()));

        if (!request.getHeaders().isEmpty()) {
        	requestMap.put("accept", request.getHeaders().get("accept"));
        	requestMap.put("accept-encoding", request.getHeaders().get("accept-encoding"));
        	requestMap.put("accept-language", request.getHeaders().get("accept-language"));
        	requestMap.put("referer", request.getHeaders().get("referer"));
        	requestMap.put("userAgent", request.getHeaders().get("user-agent"));

            // writeField("cookies", request.getCookies());
            // // only one of those can be non-empty
            // if (!request.getFormUrlEncodedParameters().isEmpty()) {
            //     requestMap.put("body", request.getFormUrlEncodedParameters());
            // } else if (request.getRawBody() != null) {
            //     requestMap.put("body", request.getRawBody());
            // } else {
            //     final CharBuffer bodyBuffer = request.getBodyBufferForSerialization();
            //     if (bodyBuffer != null && bodyBuffer.length() > 0) {
            //         writeFieldName("body");
            //         jw.writeString(bodyBuffer);
            //         jw.writeByte(COMMA);
            //     }
            // }
    	}

        if (request.getUrl().hasContent()) {
        	getUrlInfo(request.getUrl());
        }
        if (request.getSocket().hasContent()) {
        	getSocketInfo(request.getSocket());
        }
    }

	private void getUrlInfo(final Url url) {
    	requestMap.put("url", url.getFull());
    	requestMap.put("host", url.getHostname());
        requestMap.put("port", getIntegerValue(url.getPort().toString()));
        requestMap.put("path", url.getPathname());
        requestMap.put("search", url.getSearch());
    }

    private void getSocketInfo(final Socket socket) {
    	requestMap.put("encrypted", socket.isEncrypted());
        requestMap.put("ip", socket.getRemoteAddress());
    }

    private Integer getIntegerValue(String valStr) {
    	Integer val = 0;
        try {
        	val = Integer.parseInt(valStr);
        } catch (Exception e) {
        	// Do nothing.
        }
        return val;
    }

	public HashMap<String, Object> getRequestMap() {
		return requestMap;
	}

}
