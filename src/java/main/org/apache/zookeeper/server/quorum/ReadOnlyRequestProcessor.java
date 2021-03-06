/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ZooKeeperCriticalThread;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.quorum.Leader.XidRolloverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这个处理器是ReadOnlyZooKeeperServer服务器的请求处理器，它只处理只读操作，而那些状态改变的将去掉
 */
public class ReadOnlyRequestProcessor extends ZooKeeperCriticalThread implements
        RequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ReadOnlyRequestProcessor.class);

    private final LinkedBlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();

    private boolean finished = false;

    private final RequestProcessor nextProcessor;

    private final ZooKeeperServer zks;

    public ReadOnlyRequestProcessor(ZooKeeperServer zks,
                                    RequestProcessor nextProcessor) {
        super("ReadOnlyRequestProcessor:" + zks.getServerId(), zks
                .getZooKeeperServerListener());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
    }

    public void run() {
        try {
            while (!finished) {
                Request request = queuedRequests.take();

                // log request
                long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
                if (request.type == OpCode.ping) {
                    traceMask = ZooTrace.CLIENT_PING_TRACE_MASK;
                }
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logRequest(LOG, traceMask, 'R', request, "");
                }
                if (Request.requestOfDeath == request) {
                    break;
                }

                switch (request.type) {//将一些会修改数据的请求过滤掉，并返回状态不可操作的响应
                    case OpCode.sync:
                    case OpCode.create:
                    case OpCode.create2:
                    case OpCode.createTTL:
                    case OpCode.createContainer:
                    case OpCode.delete:
                    case OpCode.deleteContainer:
                    case OpCode.setData:
                    case OpCode.reconfig:
                    case OpCode.setACL:
                    case OpCode.multi:
                    case OpCode.check:
                        ReplyHeader hdr = new ReplyHeader(request.cxid, zks.getZKDatabase()
                                .getDataTreeLastProcessedZxid(), Code.NOTREADONLY.intValue());
                        try {
                            request.cnxn.sendResponse(hdr, null, null);
                        } catch (IOException e) {
                            LOG.error("IO exception while sending response", e);
                        }
                        continue;
                }

                // 请请求交给下一个处理器（PrepRequestProcessor）
                if (nextProcessor != null) {
                    nextProcessor.processRequest(request);
                }
            }
        } catch (RequestProcessorException e) {
            if (e.getCause() instanceof XidRolloverException) {
                LOG.info(e.getCause().getMessage());
            }
            handleException(this.getName(), e);
        } catch (Exception e) {
            handleException(this.getName(), e);
        }
        LOG.info("ReadOnlyRequestProcessor exited loop!");
    }

    @Override
    public void processRequest(Request request) {
        if (!finished) {
            queuedRequests.add(request);
        }
    }

    @Override
    public void shutdown() {
        finished = true;
        queuedRequests.clear();
        queuedRequests.add(Request.requestOfDeath);
        nextProcessor.shutdown();
    }

}
