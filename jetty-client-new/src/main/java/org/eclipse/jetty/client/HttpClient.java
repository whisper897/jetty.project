//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * <p>{@link HttpClient} provides an efficient, asynchronous, non-blocking implementation
 * to perform HTTP requests to a server through a simple API that offers also blocking semantic.</p>
 * <p>{@link HttpClient} provides easy-to-use methods such as {@link #GET(String)} that allow to perform HTTP
 * requests in a one-liner, but also gives the ability to fine tune the configuration of requests via
 * {@link HttpClient#newRequest(URI)}.</p>
 * <p>{@link HttpClient} acts as a central configuration point for network parameters (such as idle timeouts)
 * and HTTP parameters (such as whether to follow redirects).</p>
 * <p>{@link HttpClient} transparently pools connections to servers, but allows direct control of connections
 * for cases where this is needed.</p>
 * <p>Typical usage:</p>
 * <pre>
 * // One liner:
 * new HTTPClient().GET("http://localhost:8080/").get().status();
 *
 * // Building a request with a timeout
 * HTTPClient client = new HTTPClient();
 * Response response = client.newRequest("localhost:8080").send().get(5, TimeUnit.SECONDS);
 * int status = response.status();
 *
 * // Asynchronously
 * HTTPClient client = new HTTPClient();
 * client.newRequest("localhost:8080").send(new Response.Listener.Adapter()
 * {
 *     &#64;Override
 *     public void onSuccess(Response response)
 *     {
 *         ...
 *     }
 * });
 * </pre>
 */
public class HttpClient extends AggregateLifeCycle
{
    private static final Logger LOG = Log.getLogger(HttpClient.class);
    private final ConcurrentMap<String, Destination> destinations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, HttpConversation> conversations = new ConcurrentHashMap<>();
    private volatile Executor executor;
    private volatile ByteBufferPool byteBufferPool;
    private volatile ScheduledExecutorService scheduler;
    private volatile SelectorManager selectorManager;
    private volatile SslContextFactory sslContextFactory;

    private volatile String agent = "Jetty/" + Jetty.VERSION;
    private volatile boolean followRedirects = true;
    private volatile int maxConnectionsPerAddress = 8;
    private volatile int maxQueueSizePerAddress = 1024;
    private volatile int requestBufferSize = 4096;
    private volatile int responseBufferSize = 4096;
    private volatile SocketAddress bindAddress;
    private volatile long idleTimeout;

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (executor == null)
            executor = new QueuedThreadPool();
        addBean(executor);

        if (byteBufferPool == null)
            byteBufferPool = new MappedByteBufferPool();
        addBean(byteBufferPool);

        if (scheduler == null)
            scheduler = Executors.newSingleThreadScheduledExecutor();
        addBean(scheduler);

        selectorManager = newSelectorManager();
        addBean(selectorManager);

        super.doStart();
    }

    protected SelectorManager newSelectorManager()
    {
        return new ClientSelectorManager();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    /**
     * @return the address to bind socket channels to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind socket channels to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public Future<Response> GET(String uri)
    {
        return GET(URI.create(uri));
    }

    public Future<Response> GET(URI uri)
    {
        // TODO: Add decoder, cookies, agent, default headers, etc.
        return newRequest(uri)
                .method(HttpMethod.GET)
                .version(HttpVersion.HTTP_1_1)
                .agent(getUserAgent())
                .idleTimeout(getIdleTimeout())
                .followRedirects(isFollowRedirects())
                .send();
    }

    public Request newRequest(String host, int port)
    {
        return newRequest(URI.create(address("http", host, port)));
    }

    public Request newRequest(URI uri)
    {
        return new HttpRequest(this, uri);
    }

    protected Request newRequest(long id, URI uri)
    {
        return new HttpRequest(this, id, uri);
    }

    private String address(String scheme, String host, int port)
    {
        return scheme + "://" + host + ":" + port;
    }

    public Destination getDestination(String scheme, String host, int port)
    {
        String address = address(scheme, host, port);
        Destination destination = destinations.get(address);
        if (destination == null)
        {
            destination = new HttpDestination(this, scheme, host, port);
            Destination existing = destinations.putIfAbsent(address, destination);
            if (existing != null)
                destination = existing;
        }
        return destination;
    }

    public String getUserAgent()
    {
        return agent;
    }

    public void setUserAgent(String agent)
    {
        this.agent = agent;
    }

    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    public void setFollowRedirects(boolean follow)
    {
        this.followRedirects = follow;
    }

    public void send(Request request, Response.Listener listener)
    {
        String scheme = request.scheme().toLowerCase();
        if (!Arrays.asList("http", "https").contains(scheme))
            throw new IllegalArgumentException("Invalid protocol " + scheme);

        String host = request.host().toLowerCase();
        int port = request.port();
        if (port < 0)
            port = "https".equals(scheme) ? 443 : 80;

        getDestination(scheme, host, port).send(request, listener);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public int getMaxConnectionsPerAddress()
    {
        return maxConnectionsPerAddress;
    }

    public void setMaxConnectionsPerAddress(int maxConnectionsPerAddress)
    {
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

    public int getMaxQueueSizePerAddress()
    {
        return maxQueueSizePerAddress;
    }

    public void setMaxQueueSizePerAddress(int maxQueueSizePerAddress)
    {
        this.maxQueueSizePerAddress = maxQueueSizePerAddress;
    }

    public int getRequestBufferSize()
    {
        return requestBufferSize;
    }

    public void setRequestBufferSize(int requestBufferSize)
    {
        this.requestBufferSize = requestBufferSize;
    }

    public int getResponseBufferSize()
    {
        return responseBufferSize;
    }

    public void setResponseBufferSize(int responseBufferSize)
    {
        this.responseBufferSize = responseBufferSize;
    }

    protected void newConnection(Destination destination, Callback<Connection> callback)
    {
        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            SocketAddress bindAddress = getBindAddress();
            if (bindAddress != null)
                channel.bind(bindAddress);
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(destination.host(), destination.port()));

            Future<Connection> result = new ConnectionCallback(destination, callback);
            selectorManager.connect(channel, result);
        }
        catch (IOException x)
        {
            if (channel != null)
                close(channel);
            callback.failed(null, x);
        }
    }

    private void close(SocketChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException x)
        {
            LOG.ignore(x);
        }
    }

    public HttpConversation conversationFor(Request request)
    {
        long id = request.id();
        HttpConversation conversation = conversations.get(id);
        if (conversation == null)
        {
            conversation = new HttpConversation();
            HttpConversation existing = conversations.putIfAbsent(id, conversation);
            if (existing != null)
                conversation = existing;
        }
        return conversation;
    }

    protected class ClientSelectorManager extends SelectorManager
    {
        public ClientSelectorManager()
        {
            this(1);
        }

        public ClientSelectorManager(int selectors)
        {
            super(selectors);
        }

        @Override
        protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key)
        {
            return new SelectChannelEndPoint(channel, selector, key, scheduler, getIdleTimeout());
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment) throws IOException
        {
            ConnectionCallback callback = (ConnectionCallback)attachment;
            Destination destination = callback.destination;

            SslContextFactory sslContextFactory = getSslContextFactory();
            if ("https".equals(destination.scheme()))
            {
                if (sslContextFactory == null)
                {
                    IOException failure = new ConnectException("Missing " + SslContextFactory.class.getSimpleName() + " for " + destination.scheme() + " requests");
                    callback.failed(null, failure);
                    throw failure;
                }
                else
                {
                    SSLEngine engine = sslContextFactory.newSSLEngine(endPoint.getRemoteAddress());
                    engine.setUseClientMode(false);

                    SslConnection sslConnection = new SslConnection(getByteBufferPool(), getExecutor(), endPoint, engine);

                    EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
                    HttpConnection connection = new HttpConnection(HttpClient.this, appEndPoint);
                    appEndPoint.setConnection(connection);
                    callback.callback.completed(connection);
                    connection.onOpen();

                    return sslConnection;
                }
            }
            else
            {
                HttpConnection connection = new HttpConnection(HttpClient.this, endPoint);
                callback.callback.completed(connection);
                return connection;
            }
        }

        @Override
        protected void execute(Runnable task)
        {
            getExecutor().execute(task);
        }
    }

    private class ConnectionCallback extends FutureCallback<Connection>
    {
        private final Destination destination;
        private final Callback<Connection> callback;

        private ConnectionCallback(Destination destination, Callback<Connection> callback)
        {
            this.destination = destination;
            this.callback = callback;
        }
    }
}
