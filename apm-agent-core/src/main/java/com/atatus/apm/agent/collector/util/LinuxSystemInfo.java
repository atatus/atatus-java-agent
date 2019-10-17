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
package com.atatus.apm.agent.collector.util;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinuxSystemInfo {

    private static final Pattern TOTAL_MEMORY_PATTERN =
            Pattern.compile("MemTotal:\\s+(\\d+) kB", Pattern.MULTILINE);
    private static final Pattern FREE_MEMORY_PATTERN =
            Pattern.compile("MemFree:\\s+(\\d+) kB", Pattern.MULTILINE);
    private static final Pattern TOTAL_SWAP_PATTERN =
            Pattern.compile("SwapTotal:\\s+(\\d+) kB", Pattern.MULTILINE);
    private static final Pattern FREE_SWAP_PATTERN =
            Pattern.compile("SwapFree:\\s+(\\d+) kB", Pattern.MULTILINE);
    private static final Pattern NUM_CPU_PATTERN =
            Pattern.compile("processor\\s+:\\s+(\\d+)", Pattern.MULTILINE);
    private static final Pattern CPU_FREQ_PATTERN =
            Pattern.compile("cpu MHz\\s+:\\s+([0-9.]*)", Pattern.MULTILINE);
    private static final Pattern CPU_MODEL_NAME_PATTERN =
            Pattern.compile("model name\\s+:\\s+(.*)", Pattern.MULTILINE);
    private static final Pattern DISTRIBUTION =
            Pattern.compile("NAME=\"(.*)\"", Pattern.MULTILINE);

    private FileUtils fileUtils;

    LinuxSystemInfo(FileUtils fileUtils) {
        this.fileUtils = fileUtils;
    }

    public LinuxSystemInfo() {
        fileUtils = new FileUtils();
    }

    public String getOSDistribution() {
        String distribution = fileUtils.runRegexOnFile(DISTRIBUTION, "/etc/os-release");
        if (distribution == null) {
            return System.getProperty("os.name");
        }
        return distribution;
    }

    public long getTotalMemory() {
        String totalMemory = fileUtils.runRegexOnFile(TOTAL_MEMORY_PATTERN, "/proc/meminfo");
        long total = Long.parseLong(totalMemory) * 1024;
        // String freeMemory = fileUtils.runRegexOnFile(FREE_MEMORY_PATTERN, "/proc/meminfo");
        // long free = Long.parseLong(freeMemory) * 1024;

        return total;
    }

    public long getSwapTotalMemory() {
        String totalMemory = fileUtils.runRegexOnFile(TOTAL_SWAP_PATTERN, "/proc/meminfo");
        long total = Long.parseLong(totalMemory) * 1024;
        // String freeMemory = fileUtils.runRegexOnFile(FREE_SWAP_PATTERN, "/proc/meminfo");
        // long free = Long.parseLong(freeMemory) * 1024;

        return total;
    }

    public int getNumCPUs() {
        int numCpus = 0;
        try {
            String cpuInfo = fileUtils.slurp("/proc/cpuinfo");
            Matcher matcher = NUM_CPU_PATTERN.matcher(cpuInfo);
            while (matcher.find()) {
                numCpus++;
            }
            return numCpus;
        } catch (IOException ioe) {
            // return nothing
        }
        return 0;
    }

    public double getCPUFrequencyInMHz() {
        String cpuFrequencyAsString = fileUtils.runRegexOnFile(CPU_FREQ_PATTERN, "/proc/cpuinfo");
        double mhz = Double.parseDouble(cpuFrequencyAsString);
        return mhz;
    }

    public String getCPUModelName() {
        String cpuModelNameAsString = fileUtils.runRegexOnFile(CPU_MODEL_NAME_PATTERN, "/proc/cpuinfo");
        return cpuModelNameAsString;
    }


}
