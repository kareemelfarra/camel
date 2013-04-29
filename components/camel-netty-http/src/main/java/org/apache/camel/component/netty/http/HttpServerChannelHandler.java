/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.netty.http;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;

import org.apache.camel.Exchange;
import org.apache.camel.component.netty.NettyConsumer;
import org.apache.camel.component.netty.NettyHelper;
import org.apache.camel.component.netty.handlers.ServerChannelHandler;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.jboss.netty.handler.codec.http.HttpHeaders.is100ContinueExpected;
import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Our http server channel handler to handle HTTP status 100 to continue.
 */
public class HttpServerChannelHandler extends ServerChannelHandler {

    // use NettyConsumer as logger to make it easier to read the logs as this is part of the consumer
    private static final transient Logger LOG = LoggerFactory.getLogger(NettyConsumer.class);
    private final NettyHttpConsumer consumer;
    private HttpRequest request;

    public HttpServerChannelHandler(NettyHttpConsumer consumer) {
        super(consumer);
        this.consumer = consumer;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent messageEvent) throws Exception {
        // store request, as this channel handler is created per pipeline
        request = (HttpRequest) messageEvent.getMessage();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Message received: keep-alive {}", isKeepAlive(request));
        }

        if (is100ContinueExpected(request)) {
            // send back http 100 response to continue
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, CONTINUE);
            messageEvent.getChannel().write(response);
        } else {
            // let Camel process this message
            super.messageReceived(ctx, messageEvent);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent exceptionEvent) throws Exception {
        // only close if we are still allowed to run
        if (consumer.isRunAllowed()) {

            if (exceptionEvent.getCause() instanceof ClosedChannelException) {
                LOG.warn("Channel already closed. Ignoring this exception.");
            } else {
                LOG.warn("Closing channel as an exception was thrown from Netty", exceptionEvent.getCause());
                // close channel in case an exception was thrown
                NettyHelper.close(exceptionEvent.getChannel());
            }
        }
    }

    @Override
    protected ChannelFutureListener createResponseFutureListener(NettyConsumer consumer, Exchange exchange, SocketAddress remoteAddress) {
        // make sure to close channel if not keep-alive
        if (request != null && isKeepAlive(request)) {
            return null;
        } else {
            LOG.debug("Closing channel as not keep-alive");
            return ChannelFutureListener.CLOSE;
        }
    }

    @Override
    protected Object getResponseBody(Exchange exchange) {
        // use the binding
        if (exchange.hasOut()) {
            return consumer.getEndpoint().getNettyHttpBinding().toHttpResponse(exchange.getOut());
        } else {
            return consumer.getEndpoint().getNettyHttpBinding().toHttpResponse(exchange.getIn());
        }
    }
}
