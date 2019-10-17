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

public class Types {

	public final static String KIND_TRANSACTION = "transaction";
	public final static String TYPE_JAVA = "Java";

	private final static HashMap<String, String> types = new HashMap<String, String>();
	private final static HashMap<String, String> subTypes = new HashMap<String, String>();

	public static void load() {
		Types.types.put("db", "Database");
		Types.types.put("cache", "Database");
		Types.types.put("ext", "Remote");
		Types.types.put("external", "Remote");
		Types.types.put("websocket", "Remote");
		Types.types.put("template", "Template");
		Types.types.put("messaging", "Messaging");

		Types.subTypes.put("mysql", "MySQL");
		Types.subTypes.put("postgresql", "Postgres");
		Types.subTypes.put("mssql", "MS SQL");
		Types.subTypes.put("mongodb", "MongoDB");
		Types.subTypes.put("redis", "Redis");
		Types.subTypes.put("graphql", "GraphQL");
		Types.subTypes.put("elasticsearch", "Elasticsearch");
		Types.subTypes.put("cassandra", "Cassandra");
		Types.subTypes.put("http", "External Requests");
		Types.subTypes.put("http2", "External Requests");
		Types.subTypes.put("jsf", "JSF");
		Types.subTypes.put("jms", "JMS");
		Types.subTypes.put("dispatcher-servlet", "Dispatcher Servlet");
		Types.subTypes.put("hibernate-search", "Hibernate Search");
	}

	public static String getCleanType(String type) {
		String cleanType = types.get(type);
		if (cleanType == null) {
			cleanType = TYPE_JAVA;
		}
		return cleanType;
	}

	public static String getCleanSubType(String subType) {
		String cleanSubType = subTypes.get(subType);
		if (cleanSubType == null) {
			cleanSubType = TYPE_JAVA;
		}
		return cleanSubType;
	}
}
