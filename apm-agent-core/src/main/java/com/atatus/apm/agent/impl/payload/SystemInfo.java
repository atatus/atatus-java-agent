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
package com.atatus.apm.agent.impl.payload;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atatus.apm.agent.collector.util.LinuxSystemInfo;

import javax.annotation.Nullable;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Information about the system the agent is running on.
 */
public class SystemInfo {
    private static final Logger logger = LoggerFactory.getLogger(SystemInfo.class);

    private static final String CONTAINER_UID_REGEX = "^[0-9a-fA-F]{64}$";
    private static final String SHORTENED_UUID_PATTERN = "^[0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4,}";
    private static final String POD_REGEX =
        "(?:^/kubepods/[^/]+/pod([^/]+)$)|" +
            "(?:^/kubepods\\.slice/kubepods-[^/]+\\.slice/kubepods-[^/]+-pod([^/]+)\\.slice$)";

    /**
     * Architecture of the system the agent is running on.
     */
    private final String architecture;
    /**
     * Hostname of the system the agent is running on.
     */
    private final String hostname;
    /**
     * Name of the system OS the agent is running on.
     */
    private final String os;
    /**
     * Name of the system OS version the agent is running on.
     */
    private final String version;
    /**
     * Name of the system platform the agent is running on.
     */
    private String platform;
    /**
     * Name of the system kernel version the agent is running on.
     */
    private String kernel;
    /**
     * Boot Id of the system platform the agent is running on.
     */
    private String bootId;
    /**
     * Product Id of the system platform the agent is running on.
     */
    private String productId;

    /**
     * Info about the container the agent is running on, where applies
     */
    @Nullable
    private Container container;

    /**
     * Info about the Kubernetes pod/node the agent is running on, where applies
     */
    @Nullable
    private Kubernetes kubernetes;

    /**
     * Total Physical memory of the system the agent is running on.
     */
    private long totalMem = 0;

    /**
     * CPU Count of the system the agent is running on.
     */
    private int cpuCount = 0;

    /**
     * CPU Frequency MHz of the system the agent is running on.
     */
    private double cpuFrequencyInMHz;

    /**
     * CPU Model name of the system the agent is running on.
     */
    private String cpuModelName;

    /**
     * Java and JVM info
     */
    private String javaVersion;
    private String javaVendor;
    private String javaVMName;
    private String javaVMVersion;

    private LinuxSystemInfo linuxSystemInfo;


    public SystemInfo(String architecture, String hostname, String os) {
        this(architecture, hostname, os, null, null, null, null);
    }

    public SystemInfo(String architecture, String hostname, String os, String version) {
        this(architecture, hostname, os, version, null, null, null);
    }

    SystemInfo(String architecture, String hostname, String os, String version,
    		@Nullable String platform,  @Nullable Container container, @Nullable Kubernetes kubernetes) {
        this.architecture = architecture;
        this.hostname = hostname;
        this.os = os;
        this.version = version;
        this.platform = platform;
        this.container = container;
        this.kubernetes = kubernetes;

        if (System.getProperty("os.name").toLowerCase().startsWith("linux")) {
        	linuxSystemInfo = new LinuxSystemInfo();
        }
    }

    public static SystemInfo create(@Nullable String hostname) {
        String reportedHostname = hostname;
        if (reportedHostname == null || reportedHostname.equals("")) {
            reportedHostname = getNameOfLocalHost();
        }

        return new SystemInfo(System.getProperty("os.arch"), reportedHostname,
				System.getProperty("os.name"), System.getProperty("os.version"))
				.findContainerDetails().findBootId().findProductId()
				.findMemoryInfo().findCPUInfo().findOSDistribution().findJavaInfo();
    }

    public static SystemInfo create() {
        return create(null);
    }

    static String getNameOfLocalHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return getHostNameFromEnv();
        }
    }

    SystemInfo findBootId() {
        try {
            Path path = FileSystems.getDefault().getPath("/proc/sys/kernel/random/boot_id");
            if (path.toFile().exists()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (final String line : lines) {
                	bootId = line.trim();
                    break;
                }
            }
        } catch (Throwable e) {
            logger.debug("Failed to read/parse boot ID from '/proc/sys/kernel/random/boot_id'", e);
        }

        return this;
    }

    SystemInfo findProductId() {
        try {
            Path path = FileSystems.getDefault().getPath("/sys/class/dmi/id/product_uuid");
            if (path.toFile().exists()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (final String line : lines) {
                	productId = line.trim();
                    break;
                }
            }
        } catch (Throwable e) {
            logger.debug("Failed to read/parse product ID from '/sys/class/dmi/id/product_uuid'", e);
        }

        return this;
    }


    /**
     * Finding the container ID based on the {@code /proc/self/cgroup} file.
     * Each line in this file represents a control group hierarchy of the form
     * <p>
     * {@code \d+:([^:,]+(?:,[^:,]+)?):(/.*)}
     * <p>
     * with the first field representing the hierarchy ID, the second field representing a comma-separated list of the subsystems bound to
     * the hierarchy, and the last field representing the control group.
     *
     * @return container ID parsed from {@code /proc/self/cgroup} file lines, or {@code null} if can't find/read/parse file lines
     */
    SystemInfo findContainerDetails() {
        String containerId = null;
        try {
            Path path = FileSystems.getDefault().getPath("/proc/self/cgroup");
            if (path.toFile().exists()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (final String line : lines) {
                    parseContainerId(line);
                    if (container != null) {
                        containerId = container.getId();
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            logger.debug("Failed to read/parse container ID from '/proc/self/cgroup'", e);
        }

        try {
            // Kubernetes Downward API enables setting environment variables. We are looking for the relevant ones to this discovery
            String podUid = System.getenv("KUBERNETES_POD_UID");
            String podName = System.getenv("KUBERNETES_POD_NAME");
            String nodeName = System.getenv("KUBERNETES_NODE_NAME");
            String namespace = System.getenv("KUBERNETES_NAMESPACE");
            if (podUid != null || podName != null || nodeName != null || namespace != null) {
                // avoid overriding valid info with invalid info
                if (kubernetes != null) {
                    if (kubernetes.getPod() != null) {
                        podUid = (podUid != null) ? podUid : kubernetes.getPod().getUid();
                        podName = (podName != null) ? podName : kubernetes.getPod().getName();
                    }
                }

                kubernetes = new Kubernetes(podName, nodeName, namespace, podUid);
            }
        } catch (Throwable e) {
            logger.debug("Failed to read environment variables for Kubernetes Downward API discovery", e);
        }

        logger.debug("container ID is {}", containerId);
        return this;
    }

    /**
     * The virtual file /proc/self/cgroup lists the control groups that the process is a member of. Each line contains
     * three colon-separated fields of the form hierarchy-ID:subsystem-list:cgroup-path.
     *
     * @param line a line from the /proc/self/cgroup file
     * @return this SystemInfo object after parsing
     */
    SystemInfo parseContainerId(String line) {
        final String[] fields = line.split(":");
        if (fields.length == 3) {
            String cGroupPath = fields[2];
            int indexOfLastSlash = cGroupPath.lastIndexOf('/');

            if (indexOfLastSlash >= 0) {
                String idPart = cGroupPath.substring(indexOfLastSlash + 1);

                // Legacy, e.g.: /system.slice/docker-<CID>.scope
                if (idPart.endsWith(".scope")) {
                    idPart = idPart.substring(0, idPart.length() - ".scope".length()).substring(idPart.indexOf("-") + 1);
                }

                // Looking for kubernetes info
                String dir = cGroupPath.substring(0, indexOfLastSlash);
                if (dir.length() > 0) {
                    final Pattern pattern = Pattern.compile(POD_REGEX);
                    final Matcher matcher = pattern.matcher(dir);
                    if (matcher.find()) {
                        for (int i = 1; i <= matcher.groupCount(); i++) {
                            String podUid = matcher.group(i);
                            if (podUid != null && !podUid.isEmpty()) {
                                logger.debug("Found Kubernetes pod UID: {}", podUid);
                                // By default, Kubernetes will set the hostname of the pod containers to the pod name. Users that override
                                // the name should use the Downward API to override the pod name.
                                kubernetes = new Kubernetes(hostname, null, null, podUid);
                                break;
                            }
                        }
                    }
                }

                // If the line matched the one of the kubernetes patterns, we assume that the last part is always the container ID.
                // Otherwise we validate that it is a 64-length hex string
                if (kubernetes != null || idPart.matches(CONTAINER_UID_REGEX) || idPart.matches(SHORTENED_UUID_PATTERN)) {
                    container = new Container(idPart);
                }
            }
        }
        if (container == null) {
            logger.debug("Could not parse container ID from '/proc/self/cgroup' line: {}", line);
        }
        return this;
    }

    private static String getHostNameFromEnv() {
        // try environment properties.
        String host = System.getenv("COMPUTERNAME");
        if (host == null) {
            host = System.getenv("HOSTNAME");
        }
        if (host == null) {
            host = System.getenv("HOST");
        }
        return host;
    }

    private SystemInfo findMemoryInfo() {
    	try {
	    	if (linuxSystemInfo != null) {
	    		totalMem = linuxSystemInfo.getTotalMemory();
	    	}
    	} catch (Throwable e) {
            logger.debug("Failed to read system memory", e);
        }

    	return this;
    }

    private SystemInfo findCPUInfo() {
    	try {
	    	if (linuxSystemInfo != null) {
	    		cpuCount =  linuxSystemInfo.getNumCPUs();
	    		cpuFrequencyInMHz = linuxSystemInfo.getCPUFrequencyInMHz();
	    		cpuModelName = linuxSystemInfo.getCPUModelName();
	    	}
		} catch (Throwable e) {
	        logger.debug("Failed to read system cpu information", e);
	    }
    	return this;
    }

    private SystemInfo findOSDistribution() {
    	try {
	    	if (linuxSystemInfo != null) {
	    		platform = linuxSystemInfo.getOSDistribution();
	    	}
		} catch (Throwable e) {
	        logger.debug("Failed to read system os distribution", e);
	    }
    	return this;
    }

    private SystemInfo findJavaInfo() {
    	javaVersion = System.getProperty("java.version");
    	javaVendor = System.getProperty("java.vendor");
    	javaVMName = System.getProperty("java.vm.name");
    	javaVMVersion = System.getProperty("java.vm.version");
    	return this;
    }


    /**
     * Architecture of the system the agent is running on.
     */
    public String getArchitecture() {
        return architecture;
    }

    /**
     * Hostname of the system the agent is running on.
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Name of the system OS the agent is running on.
     */
    public String getOS() {
        return os;
    }

    /**
     * Name of the system OS version the agent is running on.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Name of the system platform the agent is running on.
     */
    public String getPlatform() {
    	if (platform == null) {
    		return os;
    	}
        return platform;
    }

    /**
     * Name of the system kernel verison the agent is running on.
     */
    public String getKernel() {
        return kernel;
    }

    /**
     * Boot Id of the system platform the agent is running on.
     */
    public String getBootId() {
        return bootId;
    }

    /**
     * Product Id of the system platform the agent is running on.
     */
    public String getProductId() {
        return productId;
    }

    /**
     * Host Id of the system platform the agent is running on.
     */
    public String getHostId() {
    	if (bootId != null && !bootId.isEmpty()) {
    		return bootId;
    	}

    	if (productId != null && !productId.isEmpty()) {
    		return productId;
    	}

        return hostname;
    }


    /**
     * Total physical memory of the system the agent is running on.
     */
    public long getTotalMem() {
		return totalMem;
	}

    /**
     * CPU Count of the system the agent is running on.
     */
	public int getCpuCount() {
		return cpuCount;
	}

    /**
     * CPU Frequency MHZ of the system the agent is running on.
     */
	public double getCpuFrequencyInMHz() {
		return cpuFrequencyInMHz;
	}

    /**
     * CPU Model name of the system the agent is running on.
     */
	public String getCpuModelName() {
		return cpuModelName;
	}

	public String getJavaVersion() {
		return javaVersion;
	}

	public String getJavaVendor() {
		return javaVendor;
	}

	public String getJavaVMName() {
		return javaVMName;
	}

	public String getJavaVMVersion() {
		return javaVMVersion;
	}

	/**
     * Container Id of the system platform the agent is running on.
     *
     * @return container info
     */
    public String getContainerId() {
    	if (container != null) {
    		return container.getId();
    	} else {
    		return null;
    	}
    }

    /**
     * Info about the container this agent is running on, where applies
     *
     * @return container info
     */
    @Nullable
    public Container getContainerInfo() {
        return container;
    }

    /**
     * Info about the kubernetes Pod and Node this agent is running on, where applies
     *
     * @return container info
     */
    @Nullable
    public Kubernetes getKubernetesInfo() {
        return kubernetes;
    }

    public static class Container {
        private String id;

        Container(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    public static class Kubernetes {
        @Nullable
        Pod pod;

        @Nullable
        Node node;

        @Nullable
        private String namespace;

        Kubernetes(@Nullable String podName, @Nullable String nodeName, @Nullable String namespace, @Nullable String podUid) {
            if (podName != null || podUid != null) {
                pod = new Pod(podName, podUid);
            }
            if (nodeName != null) {
                node = new Node(nodeName);
            }
            this.namespace = namespace;
        }

        @Nullable
        public Pod getPod() {
            return pod;
        }

        @Nullable
        public Node getNode() {
            return node;
        }

        @Nullable
        public String getNamespace() {
            return namespace;
        }

        public boolean hasContent() {
            return pod != null || node != null || namespace != null;
        }

        public static class Pod {
            @Nullable
            private String name;
            @Nullable
            private String uid;

            Pod(@Nullable String name, @Nullable String uid) {
                this.name = name;
                this.uid = uid;
            }

            @Nullable
            public String getName() {
                return name;
            }

            @Nullable
            public String getUid() {
                return uid;
            }
        }

        public static class Node {
            private String name;

            Node(String name) {
                this.name = name;
            }

            public String getName() {
                return name;
            }
        }
    }
}
