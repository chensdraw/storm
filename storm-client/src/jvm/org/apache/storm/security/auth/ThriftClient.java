/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.security.auth;

import java.util.Map;
import javax.security.auth.login.Configuration;

import org.apache.storm.utils.ObjectReader;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.storm.Config;

public class ThriftClient implements AutoCloseable {
    private TTransport _transport;
    protected TProtocol _protocol;
    private String _host;
    private Integer _port;
    private Integer _timeout;
    private Map _conf;
    private ThriftConnectionType _type;
    private String _asUser;
    protected boolean _retryForever = false;

    public ThriftClient(Map<String, Object> topoConf, ThriftConnectionType type, String host) {
        this(topoConf, type, host, null, null, null);
    }

    public ThriftClient(Map<String, Object> topoConf, ThriftConnectionType type, String host, Integer port, Integer timeout){
        this(topoConf, type, host, port, timeout, null);
    }

    public ThriftClient(Map<String, Object> topoConf, ThriftConnectionType type, String host, Integer port, Integer timeout, String asUser) {
        //create a socket with server
        if (host==null) {
            throw new IllegalArgumentException("host is not set");
        }

        if (port == null) {
            port = type.getPort(topoConf);
        }

        if (port<=0 && !type.isFake()) {
            throw new IllegalArgumentException("invalid port: "+port);
        }          

        _host = host;
        _port = port;
        _timeout = timeout;
        _conf = topoConf;
        _type = type;
        _asUser = asUser;
        if (!type.isFake()) {
            reconnect();
        }
    }

    public synchronized TTransport transport() {
        return _transport;
    }
    
    public synchronized void reconnect() {
        close();
        TSocket socket = null;
        try {
            socket = new TSocket(_host, _port);
            if(_timeout!=null) {
                socket.setTimeout(_timeout);
            }

            //locate login configuration 
            Configuration login_conf = AuthUtils.GetConfiguration(_conf);

            //construct a transport plugin
            ITransportPlugin transportPlugin = AuthUtils.GetTransportPlugin(_type, _conf, login_conf);

            //TODO get this from type instead of hardcoding to Nimbus.
            //establish client-server transport via plugin
            //do retries if the connect fails
            TBackoffConnect connectionRetry 
                = new TBackoffConnect(
                                      ObjectReader.getInt(_conf.get(Config.STORM_NIMBUS_RETRY_TIMES)),
                                      ObjectReader.getInt(_conf.get(Config.STORM_NIMBUS_RETRY_INTERVAL)),
                                      ObjectReader.getInt(_conf.get(Config.STORM_NIMBUS_RETRY_INTERVAL_CEILING)),
                                      _retryForever);
            _transport = connectionRetry.doConnectWithRetry(transportPlugin, socket, _host, _asUser);
        } catch (Exception ex) {
            // close the socket, which releases connection if it has created any.
            if(socket != null) {
                try {
                    socket.close();
                } catch (Exception e) {}
            }
            throw new RuntimeException(ex);
        }
        _protocol = null;
        if (_transport != null) {
            _protocol = new  TBinaryProtocol(_transport);
        }
    }

    @Override
    public synchronized void close() {
        if (_transport != null) {
            _transport.close();
            _transport = null;
            _protocol = null;
        }
    }
}
