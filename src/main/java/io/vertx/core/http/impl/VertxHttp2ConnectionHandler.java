/*
 * Copyright (c) 2011-2017 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.EventExecutor;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;

import java.util.function.Function;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class VertxHttp2ConnectionHandler<C extends Http2ConnectionBase> extends Http2ConnectionHandler implements Http2FrameListener, Http2Connection.Listener {

  private final Function<VertxHttp2ConnectionHandler<C>, C> connectionFactory;
  private C connection;
  private ChannelHandlerContext chctx;
  private Handler<C> addHandler;
  private Handler<C> removeHandler;
  private final boolean useDecompressor;
  private final Http2Settings serverUpgradeSettings;
  private final boolean upgrade;

  public VertxHttp2ConnectionHandler(
      Function<VertxHttp2ConnectionHandler<C>, C> connectionFactory,
      boolean useDecompressor,
      Http2ConnectionDecoder decoder,
      Http2ConnectionEncoder encoder,
      Http2Settings initialSettings,
      Http2Settings serverUpgradeSettings,
      boolean upgrade) {
    super(decoder, encoder, initialSettings);
    this.connectionFactory = connectionFactory;
    this.useDecompressor = useDecompressor;
    this.serverUpgradeSettings = serverUpgradeSettings;
    this.upgrade = upgrade;
    encoder().flowController().listener(s -> {
      if (connection != null) {
        connection.onStreamwritabilityChanged(s);
      }
    });
    connection().addListener(this);
  }

  public ChannelHandlerContext context() {
    return chctx;
  }

  /**
   * Set an handler to be called when the connection is set on this handler.
   *
   * @param handler the handler to be notified
   * @return this
   */
  public VertxHttp2ConnectionHandler<C> addHandler(Handler<C> handler) {
    this.addHandler = handler;
    return this;
  }

  /**
   * Set an handler to be called when the connection is unset from this handler.
   *
   * @param handler the handler to be notified
   * @return this
   */
  public VertxHttp2ConnectionHandler<C> removeHandler(Handler<C> handler) {
    this.removeHandler = handler;
    return this;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    chctx = ctx;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    ctx.close();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);

    if (upgrade) {
      if (serverUpgradeSettings != null) {
        onHttpServerUpgrade(serverUpgradeSettings);
      } else {
        onHttpClientUpgrade();
      }
    }

    // super call writes the connection preface
    // we need to flush to send it
    // this is called only on the client
    ctx.flush();
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    super.channelInactive(ctx);
    connection.getContext().executeFromIO(connection::handleClosed);
    if (removeHandler != null) {
      removeHandler.handle(connection);
    }
  }

  @Override
  protected void onConnectionError(ChannelHandlerContext ctx, Throwable cause, Http2Exception http2Ex) {
    connection.getContext().executeFromIO(() -> {
      connection.onConnectionError(cause);
    });
    // Default behavior send go away
    super.onConnectionError(ctx, cause, http2Ex);
  }

  @Override
  protected void onStreamError(ChannelHandlerContext ctx, Throwable cause, Http2Exception.StreamException http2Ex) {
    connection.getContext().executeFromIO(() -> {
      connection.onStreamError(http2Ex.streamId(), http2Ex);
    });
    // Default behavior reset stream
    super.onStreamError(ctx, cause, http2Ex);
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    try {
      super.userEventTriggered(ctx, evt);
    } finally {
      if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == IdleState.ALL_IDLE) {
        ctx.close();
      }
    }
  }

  //

  @Override
  public void onStreamClosed(Http2Stream stream) {
    connection.onStreamClosed(stream);
  }

  @Override
  public void onStreamAdded(Http2Stream stream) {
  }

  @Override
  public void onStreamActive(Http2Stream stream) {
  }

  @Override
  public void onStreamHalfClosed(Http2Stream stream) {
  }

  @Override
  public void onStreamRemoved(Http2Stream stream) {
  }

  @Override
  public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
    connection.onGoAwaySent(lastStreamId, errorCode, debugData);
  }

  @Override
  public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
    connection.onGoAwayReceived(lastStreamId, errorCode, debugData);
  }

  //

  void writeHeaders(Http2Stream stream, Http2Headers headers, boolean end) {
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writeHeaders(stream, headers, end);
    } else {
      executor.execute(() -> {
        _writeHeaders(stream, headers, end);
      });
    }
  }

  private void _writeHeaders(Http2Stream stream, Http2Headers headers, boolean end) {
    encoder().writeHeaders(chctx, stream.id(), headers, 0, end, chctx.newPromise());;
  }

  void writeData(Http2Stream stream, ByteBuf chunk, boolean end) {
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writeData(stream, chunk, end);
    } else {
      executor.execute(() -> {
        _writeData(stream, chunk, end);
      });
    }
  }

  private void _writeData(Http2Stream stream, ByteBuf chunk, boolean end) {
    encoder().writeData(chctx, stream.id(), chunk, 0, end, chctx.newPromise());
    Http2RemoteFlowController controller = encoder().flowController();
    if (!controller.isWritable(stream) || end) {
      try {
        encoder().flowController().writePendingBytes();
      } catch (Http2Exception e) {
        onError(chctx, e);
      }
    }
    chctx.channel().flush();
  }

  ChannelFuture writePing(ByteBuf data) {
    ChannelPromise promise = chctx.newPromise();
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writePing(data, promise);
    } else {
      executor.execute(() -> {
        _writePing(data, promise);
      });
    }
    return promise;
  }

  private void _writePing(ByteBuf data, ChannelPromise promise) {
    encoder().writePing(chctx, false, data, promise);
    chctx.channel().flush();
  }

  /**
   * Consume {@code numBytes} for {@code stream}  in the flow controller, this must be called from event loop.
   */
  void consume(Http2Stream stream, int numBytes) {
    try {
      boolean windowUpdateSent = decoder().flowController().consumeBytes(stream, numBytes);
      if (windowUpdateSent) {
        chctx.channel().flush();
      }
    } catch (Http2Exception e) {
      onError(chctx, e);
    }
  }

  void writeFrame(Http2Stream stream, byte type, short flags, ByteBuf payload) {
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writeFrame(stream, type, flags, payload);
    } else {
      executor.execute(() -> {
        _writeFrame(stream, type, flags, payload);
      });
    }
  }

  private void _writeFrame(Http2Stream stream, byte type, short flags, ByteBuf payload) {
    encoder().writeFrame(chctx, type, stream.id(), new Http2Flags(flags), payload, chctx.newPromise());
    chctx.flush();
  }

  void writeReset(int streamId, long code) {
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writeReset(streamId, code);
    } else {
      executor.execute(() -> {
        _writeReset(streamId, code);
      });
    }
  }

  private void _writeReset(int streamId, long code) {
    encoder().writeRstStream(chctx, streamId, code, chctx.newPromise());
    chctx.flush();
  }

  void writeGoAway(long errorCode, int lastStreamId, ByteBuf debugData) {
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writeGoAway(errorCode, lastStreamId, debugData);
    } else {
      executor.execute(() -> {
        _writeGoAway(errorCode, lastStreamId, debugData);
      });
    }
  }

  private void _writeGoAway(long errorCode, int lastStreamId, ByteBuf debugData) {
    encoder().writeGoAway(chctx, lastStreamId, errorCode, debugData, chctx.newPromise());
    chctx.flush();
  }

  ChannelFuture writeSettings(Http2Settings settingsUpdate) {
    ChannelPromise promise = chctx.newPromise();
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writeSettings(settingsUpdate, promise);
    } else {
      executor.execute(() -> {
        _writeSettings(settingsUpdate, promise);
      });
    }
    return promise;
  }

  private void _writeSettings(Http2Settings settingsUpdate, ChannelPromise promise) {
    encoder().writeSettings(chctx, settingsUpdate, promise);
    chctx.flush();
  }

  void writePushPromise(int streamId, Http2Headers headers, Handler<AsyncResult<Integer>> completionHandler) {
    int promisedStreamId = connection().local().incrementAndGetNextStreamId();
    ChannelPromise promise = chctx.newPromise();
    promise.addListener(fut -> {
      if (fut.isSuccess()) {
        completionHandler.handle(Future.succeededFuture(promisedStreamId));
      } else {
        completionHandler.handle(Future.failedFuture(fut.cause()));
      }
    });
    EventExecutor executor = chctx.executor();
    if (executor.inEventLoop()) {
      _writePushPromise(streamId, promisedStreamId, headers, promise);
    } else {
      executor.execute(() -> {
        _writePushPromise(streamId, promisedStreamId, headers, promise);
      });
    }
  }

  private void _writePushPromise(int streamId, int promisedStreamId, Http2Headers headers, ChannelPromise promise) {
    encoder().writePushPromise(chctx, streamId, promisedStreamId, headers, 0, promise);
  }

  // Http2FrameListener

  @Override
  public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
    assert connection != null;
    connection.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
  }

  @Override
  public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight, boolean exclusive) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) throws Http2Exception {
    connection = connectionFactory.apply(this);
    if (useDecompressor) {
      decoder().frameListener(new DelegatingDecompressorFrameListener(decoder().connection(), connection));
    } else {
      decoder().frameListener(connection);
    }
    connection.onSettingsRead(ctx, settings);
    if (addHandler != null) {
      addHandler.handle(connection);
    }
  }

  @Override
  public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId, Http2Headers headers, int padding) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) throws Http2Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload) throws Http2Exception {
    throw new UnsupportedOperationException();
  }
}
