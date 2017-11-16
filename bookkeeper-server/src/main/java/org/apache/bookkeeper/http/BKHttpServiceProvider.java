/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.http;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.bookie.Bookie;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeperAdmin;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.http.service.ErrorHttpService;
import org.apache.bookkeeper.http.service.HeartbeatService;
import org.apache.bookkeeper.http.service.HttpEndpointService;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.replication.Auditor;
import org.apache.bookkeeper.replication.AutoRecoveryMain;
import org.apache.bookkeeper.zookeeper.ZooKeeperClient;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;

/**
 * Bookkeeper based implementation of HttpServiceProvider,
 * which provide bookkeeper services to handle http requests
 * from different http endpoints.
 */
@Slf4j
public class BKHttpServiceProvider implements HttpServiceProvider {

    private final BookieServer bookieServer;
    private final AutoRecoveryMain autoRecovery;
    private final ServerConfiguration serverConf;
    private final ZooKeeper zk;
    private final BookKeeperAdmin bka;
    private final ExecutorService executor;


    private BKHttpServiceProvider(BookieServer bookieServer,
                                  AutoRecoveryMain autoRecovery,
                                  ServerConfiguration serverConf)
        throws IOException, KeeperException, InterruptedException, BKException {
        this.bookieServer = bookieServer;
        this.autoRecovery = autoRecovery;
        this.serverConf = serverConf;
        this.zk = ZooKeeperClient.newBuilder()
          .connectString(serverConf.getZkServers())
          .sessionTimeoutMs(serverConf.getZkTimeout())
          .build();

        ClientConfiguration clientConfiguration = new ClientConfiguration(serverConf)
          .setZkServers(serverConf.getZkServers());
        this.bka = new BookKeeperAdmin(clientConfiguration);

        this.executor = Executors.newSingleThreadExecutor(
          new ThreadFactoryBuilder().setNameFormat("BKHttpServiceThread").setDaemon(true).build());
    }

    @Override
    public void close() throws IOException {
        try {
            executor.shutdown();
            if (bka != null) {
                bka.close();
            }
            if (zk != null) {
                zk.close();
            }
        } catch (InterruptedException | BKException e) {
            log.error("Error while close BKHttpServiceProvider", e);
            throw new IOException("Error while close BKHttpServiceProvider", e);
        }
    }

    private ServerConfiguration getServerConf() {
        return serverConf;
    }

    private Auditor getAuditor() {
        return autoRecovery == null ? null : autoRecovery.getAuditor();
    }

    private Bookie getBookie() {
        return bookieServer == null ? null : bookieServer.getBookie();
    }

    /**
     * Builder for HttpServiceProvider.
     */
    public static class Builder {

        BookieServer bookieServer = null;
        AutoRecoveryMain autoRecovery = null;
        ServerConfiguration serverConf = null;

        public Builder setBookieServer(BookieServer bookieServer) {
            this.bookieServer = bookieServer;
            return this;
        }

        public Builder setAutoRecovery(AutoRecoveryMain autoRecovery) {
            this.autoRecovery = autoRecovery;
            return this;
        }

        public Builder setServerConfiguration(ServerConfiguration conf) {
            this.serverConf = conf;
            return this;
        }

        public BKHttpServiceProvider build()
            throws IOException, KeeperException, InterruptedException, BKException {
            return new BKHttpServiceProvider(
                bookieServer,
                autoRecovery,
                serverConf
            );
        }
    }

    @Override
    public HttpEndpointService provideHttpEndpointService(HttpServer.ApiType type) {
        ServerConfiguration configuration = getServerConf();
        if (configuration == null) {
            return new ErrorHttpService();
        }

        switch (type) {
            case HEARTBEAT:
                return new HeartbeatService();
            case SERVER_CONFIG:
                return new ConfigurationService(configuration);

            // ledger
            case DELETE_LEDGER:
                return new DeleteLedgerService(configuration);
            case LIST_LEDGER:
                return new ListLedgerService(configuration, zk);
            case GET_LEDGER_META:
                return new GetLedgerMetaService(configuration, zk);
            case READ_LEDGER_ENTRY:
                return new ReadLedgerEntryService(configuration, bka);

            // bookie
            case LIST_BOOKIES:
                return new ListBookiesService(configuration, bka);
            case LIST_BOOKIE_INFO:
                return new ListBookieInfoService(configuration);
            case LAST_LOG_MARK:
                return new GetLastLogMarkService(configuration);
            case LIST_DISK_FILE:
                return new ListDiskFilesService(configuration);
            case EXPAND_STORAGE:
                return new ExpandStorageService(configuration, zk);

            // autorecovery
            case RECOVERY_BOOKIE:
                return new RecoveryBookieService(configuration, bka, executor);
            case LIST_UNDER_REPLICATED_LEDGER:
                return new ListUnderReplicatedLedgerService(configuration, zk);
            case WHO_IS_AUDITOR:
                return new WhoIsAuditorService(configuration, zk);
            case TRIGGER_AUDIT:
                return new TriggerAuditService(configuration, bka);
            case LOST_BOOKIE_RECOVERY_DELAY:
                return new LostBookieRecoveryDelayService(configuration, bka);
            case DECOMMISSION:
                return new DecommissionService(configuration, bka, executor);

            default:
                return new ConfigurationService(configuration);
        }
    }

}