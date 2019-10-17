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

// import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Collections;
import java.util.Map;
// import lombok.EqualsAndHashCode;

/**
 * Holds sample rate for all known services. This is reported by Dadadog agent in response to
 * writing traces.
 */
// @EqualsAndHashCode
class SampleRateByService {

  static final SampleRateByService EMPTY_INSTANCE = new SampleRateByService(Collections.EMPTY_MAP);

  private final Map<String, Double> rateByService;

  // @JsonCreator
  SampleRateByService(final Map<String, Double> rateByService) {
    this.rateByService = Collections.unmodifiableMap(rateByService);
  }

  public Double getRate(final String service) {
    // TODO: improve logic in this class to handle default value better
    return rateByService.get(service);
  }
}
