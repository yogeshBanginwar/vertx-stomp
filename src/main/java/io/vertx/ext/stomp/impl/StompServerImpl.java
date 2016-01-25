/*
 *  Copyright (c) 2011-2015 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *       The Eclipse Public License is available at
 *       http://www.eclipse.org/legal/epl-v10.html
 *
 *       The Apache License v2.0 is available at
 *       http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.stomp.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetServer;
import io.vertx.ext.stomp.*;

import java.util.Objects;

/**
 * Default implementation of the {@link StompServer}.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class StompServerImpl implements StompServer {

  private static final Logger log = LoggerFactory.getLogger(StompServerImpl.class);

  private final Vertx vertx;
  private final StompServerOptions options;
  private final NetServer server;

  private StompServerHandler handler;
  private volatile boolean listening;

  public StompServerImpl(Vertx vertx, NetServer net, StompServerOptions options) {
    Objects.requireNonNull(vertx);
    Objects.requireNonNull(options);
    this.options = options;
    this.vertx = vertx;
    if (net == null) {
      server = vertx.createNetServer(options);
    } else {
      server = net;
    }
  }

  @Override
  public synchronized StompServer handler(StompServerHandler handler) {
    Objects.requireNonNull(handler);
    this.handler = handler;
    return this;
  }

  @Override
  public StompServer listen() {
    return listen(null);
  }

  @Override
  public StompServer listen(Handler<AsyncResult<StompServer>> handler) {
    return listen(options.getPort(), options.getHost(), handler);
  }

  @Override
  public StompServer listen(int port) {
    return listen(port, StompServerOptions.DEFAULT_STOMP_HOST);
  }

  @Override
  public StompServer listen(int port, String host) {
    return listen(port, host, null);
  }

  @Override
  public StompServer listen(int port, Handler<AsyncResult<StompServer>> handler) {
    return listen(port, StompServerOptions.DEFAULT_STOMP_HOST, handler);
  }

  @Override
  public StompServer listen(int port, String host, Handler<AsyncResult<StompServer>> handler) {
    if (port == -1) {
      handler.handle(Future.failedFuture("TCP server disabled. The port is set to '-1'."));
      return this;
    }
    StompServerHandler stomp;
    synchronized (this) {
      stomp = this.handler;
    }

    Objects.requireNonNull(stomp, "Cannot open STOMP server - no StompServerConnectionHandler attached to the " +
        "server.");
    server
        .connectHandler(socket -> {
          StompServerConnection connection = new StompServerTCPConnectionImpl(socket, this);
          FrameParser parser = new FrameParser(options);
          socket.exceptionHandler((exception) -> {
            log.error("The STOMP server caught a TCP socket error - closing connection", exception);
            connection.close();
          });
          socket.endHandler(v -> connection.close());
          parser
              .errorHandler((exception) -> {
                    connection.write(
                        Frames.createInvalidFrameErrorFrame(exception).toBuffer());
                    connection.close();
                  }
              )
              .handler(frame -> stomp.handle(new ServerFrameImpl(frame, connection)));
          socket.handler(parser::handle);
        })
        .listen(port, host, ar -> {
          if (ar.failed()) {
            if (handler != null) {
              vertx.runOnContext(v -> handler.handle(Future.failedFuture(ar.cause())));
            } else {
              log.error(ar.cause());
            }
          } else {
            listening = true;
            log.info("STOMP server listening on " + ar.result().actualPort());
            if (handler != null) {
              vertx.runOnContext(v -> handler.handle(Future.succeededFuture(this)));
            }
          }
        });
    return this;
  }

  @Override
  public void close() {
    close(null);
  }

  @Override
  public boolean isListening() {
    return listening;
  }

  @Override
  public int actualPort() {
    return server.actualPort();
  }

  @Override
  public StompServerOptions options() {
    return options;
  }

  @Override
  public Vertx vertx() {
    return vertx;
  }

  @Override
  public synchronized StompServerHandler stompHandler() {
    return handler;
  }


  @Override
  public void close(Handler<AsyncResult<Void>> done) {
    if (!listening) {
      if (done != null) {
        vertx.runOnContext((v) -> done.handle(Future.succeededFuture()));
      }
      return;
    }

    Handler<AsyncResult<Void>> listener = (v) -> {
      if (v.succeeded()) {
        log.info("STOMP Server stopped");
      } else {
        log.info("STOMP Server failed to stop", v.cause());
      }

      listening = false;
      if (done != null) {
        done.handle(v);
      }
    };

    server.close(listener);
  }

  @Override
  public Handler<ServerWebSocket> webSocketHandler() {
    if (!options.isWebsocketBridge()) {
      return null;
    }

    StompServerHandler stomp;
    synchronized (this) {
      stomp = this.handler;
    }

    return socket -> {
      if (!socket.path().equals(options.getWebsocketPath())) {
        log.error("Receiving a web socket connection on an invalid path (" + socket.path() + "), the path is " +
            "configured to " + options.getWebsocketPath() + ". Rejecting connection");
        socket.reject();
        return;
      }
      StompServerConnection connection = new StompServerWebSocketConnectionImpl(socket, this);
      FrameParser parser = new FrameParser(options);
      socket.exceptionHandler((exception) -> {
        log.error("The STOMP server caught a WebSocket error - closing connection", exception);
        connection.close();
      });
      socket.endHandler(v -> connection.close());
      parser
          .errorHandler((exception) -> {
                connection.write(
                    Frames.createInvalidFrameErrorFrame(exception).toBuffer());
                connection.close();
              }
          )
          .handler(frame -> stomp.handle(new ServerFrameImpl(frame, connection)));
      socket.handler(parser);
    };
  }


}
