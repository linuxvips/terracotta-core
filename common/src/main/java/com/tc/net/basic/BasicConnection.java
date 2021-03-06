/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.net.basic;

import com.tc.bytes.TCByteBuffer;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.BufferManager;
import com.tc.net.core.BufferManagerFactory;
import com.tc.net.core.TCConnection;
import com.tc.net.core.event.TCConnectionErrorEvent;
import com.tc.net.core.event.TCConnectionEvent;
import com.tc.net.core.event.TCConnectionEventListener;
import com.tc.net.protocol.TCNetworkMessage;
import com.tc.net.protocol.transport.WireProtocolHeader;
import com.tc.net.protocol.transport.WireProtocolMessage;
import com.tc.net.protocol.transport.WireProtocolMessageImpl;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import com.tc.net.protocol.TCProtocolAdaptor;
import com.tc.net.protocol.TCProtocolException;
import com.tc.text.PrettyPrintable;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class BasicConnection implements TCConnection {
  private static final Logger LOGGER = LoggerFactory.getLogger(BasicConnection.class);
  
  private long connect = 0;
  private volatile long last = System.currentTimeMillis();
  private volatile long received = System.currentTimeMillis();
  
  private final Function<TCConnection, Socket> closeRunnable;
  private final Consumer<WireProtocolMessage> write;
  private final TCProtocolAdaptor adaptor;
  private BufferManager buffer;
  private final BufferManagerFactory bufferManagerFactory;
  private Socket src;
  private boolean established = false;
  private boolean connected = true;
  private final List<TCConnectionEventListener> listeners = new CopyOnWriteArrayList<>();
  private ExecutorService readerExec;
  

  public BasicConnection(Socket src, Consumer<WireProtocolMessage> write, Function<TCConnection, Socket> close) {
    this.src = src;
    this.write = write;
    this.closeRunnable = close;
    this.adaptor = null;
    this.bufferManagerFactory = null;
  }
  
  public BasicConnection(TCProtocolAdaptor adapter, BufferManagerFactory buffers, Function<TCConnection, Socket> close) {
    this.bufferManagerFactory = buffers;
    Object writeMutex = new Object();
    this.write = (message)->{
      synchronized (writeMutex) {
        try {
          if (this.src != null) {
            boolean interrupted = Thread.interrupted();
            int totalLen = message.getTotalLength();
            int moved = 0;
            int sent = 0;
            TCByteBuffer[] data = message.getEntireMessageData();
            while (moved < totalLen) {
              for (TCByteBuffer b : data) {
                moved += buffer.forwardToWriteBuffer(b.getNioBuffer());
              }
              sent += buffer.sendFromBuffer();
            }
            while (sent < totalLen) {
              sent += buffer.sendFromBuffer();
            }
            message.wasSent();
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        } catch (IOException ioe) {
          fireError(ioe, message);
          close(0);
        } catch (Throwable t) {
          close(0);
        }
      }
    };
    this.closeRunnable = close;
    this.adaptor = adapter;
  }

  @Override
  public long getConnectTime() {
    return connect;
  }

  @Override
  public long getIdleTime() {
    return System.currentTimeMillis() - last;
  }

  @Override
  public long getIdleReceiveTime() {
    return System.currentTimeMillis() - received;
  }
  
  void markReceived() {
    received = System.currentTimeMillis();
  }

  @Override
  public void addListener(TCConnectionEventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(TCConnectionEventListener listener) {
    listeners.remove(listener);
  }

  @Override
  public void asynchClose() {
    close(0);
  }

  @Override
  public synchronized Socket detach() throws IOException {
    try {
      this.established = false;
      Socket socket = this.closeRunnable.apply(this);
      return socket == null ? src : socket;
    } catch (Exception e) {
      return null;
    } finally {
      this.established = false;
      this.connected = false;
    }
  }

  @Override
  public boolean close(long timeout) {
    try {
        Socket socket = detach();
        if (socket != null) {
        try {
          if (this.buffer != null) {
            this.buffer.close();
          }
        } catch (EOFException eof) {
          fireEOF();
          LOGGER.debug("closed", eof);
        } catch (IOException ioe) {
          LOGGER.warn("failed to close buffer manager", ioe);
        }
        socket.getChannel().close();
        socket.close();
        if (readerExec != null) {
          readerExec.shutdown();
          readerExec.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        }
      }
      return true;
    } catch (IOException ioe) {
      return false;
    } catch (InterruptedException ie) {
      return false;
    } finally {
      fireClosed();
    }
  }
  
  private void fireClosed() {
    TCConnectionEvent event = new TCConnectionEvent(this);
    listeners.forEach(l->l.closeEvent(event));
  }
  
  private void fireConnect() {
    TCConnectionEvent event = new TCConnectionEvent(this);
    listeners.forEach(l->l.connectEvent(event));
  }
  
  private void fireEOF() {
    TCConnectionEvent event = new TCConnectionEvent(this);
    listeners.forEach(l->l.endOfFileEvent(event));
  }
  
  private void fireError(Exception err, TCNetworkMessage cxt) {
    TCConnectionErrorEvent event = new TCConnectionErrorEvent(this, err, cxt);
    listeners.forEach(l->l.errorEvent(event));
  }
  
  @Override
  public synchronized Socket connect(TCSocketAddress addr, int timeout) throws IOException, TCTimeoutException {
    boolean interrupted = Thread.interrupted();
    SocketChannel channel = SocketChannel.open(new InetSocketAddress(addr.getAddress(), addr.getPort()));
    src = channel.socket();
    this.buffer = bufferManagerFactory.createBufferManager(channel, true);
    if (this.buffer == null) {
      throw new IOException("buffer manager not provided");
    }
    this.connected = src.isConnected();
    if (connected) {
      readMessages();
      fireConnect();
      connect = System.currentTimeMillis();
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return src;
  }

  @Override
  public boolean asynchConnect(TCSocketAddress addr) throws IOException {
    try {
      connect(addr, 0);
    } catch (TCTimeoutException timeout) {
      throw new IOException(timeout);
    }
    return true;
  }

  @Override
  public synchronized boolean isConnected() {
    return this.connected;
  }

  @Override
  public synchronized boolean isClosed() {
    return !this.connected;
  }

  @Override
  public TCSocketAddress getLocalAddress() {
    return new TCSocketAddress(this.src.getLocalAddress(), this.src.getLocalPort());
  }

  @Override
  public TCSocketAddress getRemoteAddress() {
    return new TCSocketAddress(this.src.getInetAddress(), this.src.getPort());
  }

  @Override
  public synchronized void setTransportEstablished() {
    established = true;
  }

  @Override
  public synchronized boolean isTransportEstablished() {
    return established;
  }

  @Override
  public boolean isClosePending() {
    return false;
  }

  @Override
  public void putMessage(TCNetworkMessage message) {
    last = System.currentTimeMillis();
    if (message instanceof WireProtocolMessage) {
      this.write.accept(finalizeWireProtocolMessage((WireProtocolMessage)message, 1));
    } else {
      this.write.accept(buildWireProtocolMessage(message));
    }
  }
  
  private void readMessages() {
    readerExec = Executors.newFixedThreadPool(1, (r) -> {
      Thread t = new Thread(r, "BasicConnectionReader-" + this.src.getLocalSocketAddress() + "<-" + this.src.getRemoteSocketAddress());
      t.setDaemon(true);
      return t;
    });
    readerExec.submit(() -> {
      while (!isClosed()) {
        try {
          long amount = buffer.recvToBuffer();
          if (amount > 0) {
            if (amount > Integer.MAX_VALUE) {
              throw new AssertionError("overflow long");
            }
            int transfer = 0;
            while (transfer < amount) {
              int i = 0;
              int read = 0;
              TCByteBuffer[] buffers = adaptor.getReadBuffers();
              while (i < buffers.length) {
                read += buffer.forwardFromReadBuffer(buffers[i].getNioBuffer());
                if (!buffers[i].hasRemaining()) {
                  i += 1;
                } else {
                  break;
                }
              }
              adaptor.addReadData(this, buffers, read);
              transfer += read;
            }
            markReceived();
          } else {
            if (amount < 0) {
              close(0);
            }
          }
        } catch (EOFException eof) {
          if (!isClosed()) {
            fireEOF();
            close(0);
          }
        } catch (TCProtocolException | IOException ioe) {
          if (!isClosed()) {
            fireError(ioe, null);
            LOGGER.warn("error reading from connection", ioe);
            close(0);
          }
          // TODO:  figure it out
        }
      }

    });
  }
    
  private WireProtocolMessage buildWireProtocolMessage(TCNetworkMessage message) {
    Assert.eval(!(message instanceof WireProtocolMessage));
    final TCNetworkMessage payload = message;

    WireProtocolMessage wireMessage = WireProtocolMessageImpl.wrapMessage(message, this);
    Assert.eval(wireMessage.getSentCallback() == null);

    final Runnable callback = payload.getSentCallback();
    if (callback != null) {
      wireMessage.setSentCallback(callback);
    }
    return finalizeWireProtocolMessage(wireMessage, 1);
  }

  private WireProtocolMessage finalizeWireProtocolMessage(WireProtocolMessage message, int messageCount) {
    final WireProtocolHeader hdr = (WireProtocolHeader) message.getHeader();
    hdr.setSourceAddress(getLocalAddress().getAddressBytes());
    hdr.setSourcePort(getLocalAddress().getPort());
    hdr.setDestinationAddress(getRemoteAddress().getAddressBytes());
    hdr.setDestinationPort(getRemoteAddress().getPort());
    hdr.setMessageCount(messageCount);
    hdr.computeChecksum();
    return message;
  } 

  @Override
  public Map<String, ?> getState() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("localAddress", this.getLocalAddress());
    state.put("remoteAddress", this.getRemoteAddress());
    state.put("connectTime", new Date(this.getConnectTime()));
    state.put("receiveIdleTime", this.getIdleReceiveTime());
    state.put("idleTime", this.getIdleTime());
    state.put("closed", isClosed());
    state.put("connected", isConnected());
    state.put("closePending", isClosePending());
    state.put("transportConnected", isTransportEstablished());
    if (buffer instanceof PrettyPrintable) {
      state.put("buffer", ((PrettyPrintable)this.buffer).getStateMap());
    } else {
      state.put("buffer", this.buffer.toString());
    }
    return state;
  }
  
  
}
