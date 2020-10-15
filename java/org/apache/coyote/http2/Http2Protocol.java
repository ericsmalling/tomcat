/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.management.ObjectName;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Adapter;
import org.apache.coyote.CompressionConfig;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.Processor;
import org.apache.coyote.Request;
import org.apache.coyote.RequestGroupInfo;
import org.apache.coyote.Response;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler;
import org.apache.coyote.http11.upgrade.UpgradeProcessorInternal;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.apache.tomcat.util.res.StringManager;

public class Http2Protocol implements UpgradeProtocol {

    private static final Log log = LogFactory.getLog(Http2Protocol.class);
    private static final StringManager sm = StringManager.getManager(Http2Protocol.class);

    static final long DEFAULT_READ_TIMEOUT = 5000;
    static final long DEFAULT_WRITE_TIMEOUT = 5000;
    static final long DEFAULT_KEEP_ALIVE_TIMEOUT = 20000;
    static final long DEFAULT_STREAM_READ_TIMEOUT = 20000;
    static final long DEFAULT_STREAM_WRITE_TIMEOUT = 20000;
    // The HTTP/2 specification recommends a minimum default of 100
    static final long DEFAULT_MAX_CONCURRENT_STREAMS = 100;
    // Maximum amount of streams which can be concurrently executed over
    // a single connection
    static final int DEFAULT_MAX_CONCURRENT_STREAM_EXECUTION = 20;

    static final int DEFAULT_OVERHEAD_COUNT_FACTOR = 1;
    static final int DEFAULT_OVERHEAD_CONTINUATION_THRESHOLD = 1024;
    static final int DEFAULT_OVERHEAD_DATA_THRESHOLD = 1024;
    static final int DEFAULT_OVERHEAD_WINDOW_UPDATE_THRESHOLD = 1024;

    private static final String HTTP_UPGRADE_NAME = "h2c";
    private static final String ALPN_NAME = "h2";
    private static final byte[] ALPN_IDENTIFIER = ALPN_NAME.getBytes(StandardCharsets.UTF_8);

    // All timeouts in milliseconds
    // These are the socket level timeouts
    private long readTimeout = DEFAULT_READ_TIMEOUT;
    private long writeTimeout = DEFAULT_WRITE_TIMEOUT;
    private long keepAliveTimeout = DEFAULT_KEEP_ALIVE_TIMEOUT;
    // These are the stream level timeouts
    private long streamReadTimeout = DEFAULT_STREAM_READ_TIMEOUT;
    private long streamWriteTimeout = DEFAULT_STREAM_WRITE_TIMEOUT;

    private long maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private int maxConcurrentStreamExecution = DEFAULT_MAX_CONCURRENT_STREAM_EXECUTION;
    // To advertise a different default to the client specify it here but DO NOT
    // change the default defined in ConnectionSettingsBase.
    private int initialWindowSize = ConnectionSettingsBase.DEFAULT_INITIAL_WINDOW_SIZE;
    // Limits
    private Set<String> allowedTrailerHeaders =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private int maxHeaderCount = Constants.DEFAULT_MAX_HEADER_COUNT;
    private int maxHeaderSize = Constants.DEFAULT_MAX_HEADER_SIZE;
    private int maxTrailerCount = Constants.DEFAULT_MAX_TRAILER_COUNT;
    private int maxTrailerSize = Constants.DEFAULT_MAX_TRAILER_SIZE;
    private int overheadCountFactor = DEFAULT_OVERHEAD_COUNT_FACTOR;
    private int overheadContinuationThreshold = DEFAULT_OVERHEAD_CONTINUATION_THRESHOLD;
    private int overheadDataThreshold = DEFAULT_OVERHEAD_DATA_THRESHOLD;
    private int overheadWindowUpdateThreshold = DEFAULT_OVERHEAD_WINDOW_UPDATE_THRESHOLD;

    private boolean initiatePingDisabled = false;
    private boolean useSendfile = true;
    // Compression
    private final CompressionConfig compressionConfig = new CompressionConfig();
    // Reference to HTTP/1.1 protocol that this instance is configured under
    private AbstractHttp11Protocol<?> http11Protocol = null;

    private RequestGroupInfo global = new RequestGroupInfo();

    @Override
    public String getHttpUpgradeName(boolean isSSLEnabled) {
        if (isSSLEnabled) {
            return null;
        } else {
            return HTTP_UPGRADE_NAME;
        }
    }

    @Override
    public byte[] getAlpnIdentifier() {
        return ALPN_IDENTIFIER;
    }

    @Override
    public String getAlpnName() {
        return ALPN_NAME;
    }

    @Override
    public Processor getProcessor(SocketWrapperBase<?> socketWrapper, Adapter adapter) {
        String upgradeProtocol = getUpgradeProtocolName();
        UpgradeProcessorInternal processor = new UpgradeProcessorInternal(socketWrapper,
                new UpgradeToken(getInternalUpgradeHandler(socketWrapper, adapter, null), null, null, upgradeProtocol),
                http11Protocol.getUpgradeGroupInfo(upgradeProtocol));
        return processor;
    }


    @Override
    public InternalHttpUpgradeHandler getInternalUpgradeHandler(SocketWrapperBase<?> socketWrapper,
            Adapter adapter, Request coyoteRequest) {
        return socketWrapper.hasAsyncIO()
                ? new Http2AsyncUpgradeHandler(this, adapter, coyoteRequest)
                : new Http2UpgradeHandler(this, adapter, coyoteRequest);
    }


    @Override
    public boolean accept(Request request) {
        // Should only be one HTTP2-Settings header
        Enumeration<String> settings = request.getMimeHeaders().values("HTTP2-Settings");
        int count = 0;
        while (settings.hasMoreElements()) {
            count++;
            settings.nextElement();
        }
        if (count != 1) {
            return false;
        }

        Enumeration<String> connection = request.getMimeHeaders().values("Connection");
        boolean found = false;
        while (connection.hasMoreElements() && !found) {
            found = connection.nextElement().contains("HTTP2-Settings");
        }
        return found;
    }


    public long getReadTimeout() {
        return readTimeout;
    }


    public void setReadTimeout(long readTimeout) {
        this.readTimeout = readTimeout;
    }


    public long getWriteTimeout() {
        return writeTimeout;
    }


    public void setWriteTimeout(long writeTimeout) {
        this.writeTimeout = writeTimeout;
    }


    public long getKeepAliveTimeout() {
        return keepAliveTimeout;
    }


    public void setKeepAliveTimeout(long keepAliveTimeout) {
        this.keepAliveTimeout = keepAliveTimeout;
    }


    public long getStreamReadTimeout() {
        return streamReadTimeout;
    }


    public void setStreamReadTimeout(long streamReadTimeout) {
        this.streamReadTimeout = streamReadTimeout;
    }


    public long getStreamWriteTimeout() {
        return streamWriteTimeout;
    }


    public void setStreamWriteTimeout(long streamWriteTimeout) {
        this.streamWriteTimeout = streamWriteTimeout;
    }


    public long getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }


    public void setMaxConcurrentStreams(long maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }


    public int getMaxConcurrentStreamExecution() {
        return maxConcurrentStreamExecution;
    }


    public void setMaxConcurrentStreamExecution(int maxConcurrentStreamExecution) {
        this.maxConcurrentStreamExecution = maxConcurrentStreamExecution;
    }


    public int getInitialWindowSize() {
        return initialWindowSize;
    }


    public void setInitialWindowSize(int initialWindowSize) {
        this.initialWindowSize = initialWindowSize;
    }


    public boolean getUseSendfile() {
        return useSendfile;
    }


    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }


    public void setAllowedTrailerHeaders(String commaSeparatedHeaders) {
        // Jump through some hoops so we don't end up with an empty set while
        // doing updates.
        Set<String> toRemove = new HashSet<>();
        toRemove.addAll(allowedTrailerHeaders);
        if (commaSeparatedHeaders != null) {
            String[] headers = commaSeparatedHeaders.split(",");
            for (String header : headers) {
                String trimmedHeader = header.trim().toLowerCase(Locale.ENGLISH);
                if (toRemove.contains(trimmedHeader)) {
                    toRemove.remove(trimmedHeader);
                } else {
                    allowedTrailerHeaders.add(trimmedHeader);
                }
            }
            allowedTrailerHeaders.removeAll(toRemove);
        }
    }


    public String getAllowedTrailerHeaders() {
        // Chances of a size change between these lines are small enough that a
        // sync is unnecessary.
        List<String> copy = new ArrayList<>(allowedTrailerHeaders.size());
        copy.addAll(allowedTrailerHeaders);
        return StringUtils.join(copy);
    }


    boolean isTrailerHeaderAllowed(String headerName) {
        return allowedTrailerHeaders.contains(headerName);
    }


    public void setMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
    }


    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }


    public void setMaxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
    }


    public int getMaxHeaderSize() {
        return maxHeaderSize;
    }


    public void setMaxTrailerCount(int maxTrailerCount) {
        this.maxTrailerCount = maxTrailerCount;
    }


    public int getMaxTrailerCount() {
        return maxTrailerCount;
    }


    public void setMaxTrailerSize(int maxTrailerSize) {
        this.maxTrailerSize = maxTrailerSize;
    }


    public int getMaxTrailerSize() {
        return maxTrailerSize;
    }


    public int getOverheadCountFactor() {
        return overheadCountFactor;
    }


    public void setOverheadCountFactor(int overheadCountFactor) {
        this.overheadCountFactor = overheadCountFactor;
    }


    public int getOverheadContinuationThreshold() {
        return overheadContinuationThreshold;
    }


    public void setOverheadContinuationThreshold(int overheadContinuationThreshold) {
        this.overheadContinuationThreshold = overheadContinuationThreshold;
    }


    public int getOverheadDataThreshold() {
        return overheadDataThreshold;
    }


    public void setOverheadDataThreshold(int overheadDataThreshold) {
        this.overheadDataThreshold = overheadDataThreshold;
    }


    public int getOverheadWindowUpdateThreshold() {
        return overheadWindowUpdateThreshold;
    }


    public void setOverheadWindowUpdateThreshold(int overheadWindowUpdateThreshold) {
        this.overheadWindowUpdateThreshold = overheadWindowUpdateThreshold;
    }


    public void setInitiatePingDisabled(boolean initiatePingDisabled) {
        this.initiatePingDisabled = initiatePingDisabled;
    }


    public boolean getInitiatePingDisabled() {
        return initiatePingDisabled;
    }


    public void setCompression(String compression) {
        compressionConfig.setCompression(compression);
    }
    public String getCompression() {
        return compressionConfig.getCompression();
    }
    protected int getCompressionLevel() {
        return compressionConfig.getCompressionLevel();
    }


    public String getNoCompressionUserAgents() {
        return compressionConfig.getNoCompressionUserAgents();
    }
    protected Pattern getNoCompressionUserAgentsPattern() {
        return compressionConfig.getNoCompressionUserAgentsPattern();
    }
    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        compressionConfig.setNoCompressionUserAgents(noCompressionUserAgents);
    }


    public String getCompressibleMimeType() {
        return compressionConfig.getCompressibleMimeType();
    }
    public void setCompressibleMimeType(String valueS) {
        compressionConfig.setCompressibleMimeType(valueS);
    }
    public String[] getCompressibleMimeTypes() {
        return compressionConfig.getCompressibleMimeTypes();
    }


    public int getCompressionMinSize() {
        return compressionConfig.getCompressionMinSize();
    }
    public void setCompressionMinSize(int compressionMinSize) {
        compressionConfig.setCompressionMinSize(compressionMinSize);
    }


    @Deprecated
    public boolean getNoCompressionStrongETag() {
        return compressionConfig.getNoCompressionStrongETag();
    }
    @Deprecated
    public void setNoCompressionStrongETag(boolean noCompressionStrongETag) {
        compressionConfig.setNoCompressionStrongETag(noCompressionStrongETag);
    }


    public boolean useCompression(Request request, Response response) {
        return compressionConfig.useCompression(request, response);
    }


    public ContinueResponseTiming getContinueResponseTimingInternal() {
        return http11Protocol.getContinueResponseTimingInternal();
    }


    public AbstractProtocol<?> getHttp11Protocol() {
        return this.http11Protocol;
    }


    @Override
    public void setHttp11Protocol(AbstractHttp11Protocol<?> http11Protocol) {
        this.http11Protocol = http11Protocol;

        try {
            ObjectName oname = this.http11Protocol.getONameForUpgrade(getUpgradeProtocolName());
            Registry.getRegistry(null, null).registerComponent(global, oname, null);
        } catch (Exception e) {
            log.warn(sm.getString("http2Protocol.jmxRegistration.fail"), e);
        }
    }


    public String getUpgradeProtocolName() {
        if (http11Protocol.isSSLEnabled()) {
            return ALPN_NAME;
        } else {
            return HTTP_UPGRADE_NAME;
        }
    }


    public RequestGroupInfo getGlobal() {
        return global;
    }
}
