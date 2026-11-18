package local.hicarlab;

import java.io.*;
import java.net.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** Authenticated-peer UDP receiver; bounded RTP state, no audio files. */
final class AudioReceiver implements AutoCloseable, SpeakerControl.Output, DeviceBusiness.Speaker {
  interface Sink extends AutoCloseable {
    boolean ready();

    void reset();

    void offer(AudioMedia.Frame frame);

    void close();
  }

  interface Events {
    boolean active();

    void log(String text);
  }

  private final DatagramSocket socket;
  private final String peer;
  private final Sink sink;
  private final Events events;
  private final SessionCipher cipher;
  private final AudioMedia media = new AudioMedia();
  private final Object stateLock = new Object();
  private volatile int epoch;
  private volatile boolean closed, active;
  private volatile int remotePort;
  private long received, rejected, wrongPeer;

  /** 校验输出可用后创建会话密码与 UDP 套接字，启动接收线程并记录就绪日志。 */
  AudioReceiver(String owner, String peer, byte[] key, Sink sink, Events e) throws IOException {
    this.peer = peer;
    this.sink = sink;
    events = e;
    if (!sink.ready()) throw new IOException("audio output unavailable");
    cipher = new SessionCipher(key, 65536);
    DatagramSocket opened = null;
    try {
      opened = new DatagramSocket(new InetSocketAddress(owner, 0));
      opened.setSoTimeout(500);
      socket = opened;
    } catch (IOException ex) {
      if (opened != null) opened.close();
      cipher.close();
      throw ex;
    }
    new Thread(this::receive, "lab-audio-udp").start();
    events.log("音频 UDP 接收端已就绪；尚未协商或播放音频。");
  }

  /** 返回接收端未关闭且输出仍就绪。 */
  public boolean ready() {
    return !closed && sink.ready();
  }

  /** 返回 UDP 套接字的本地端口。 */
  public int port() {
    return socket.getLocalPort();
  }

  /** 将日志文本转发给事件回调。 */
  public void log(String s) {
    events.log(s);
  }

  /** 开始新一轮会话：停用接收、递增 epoch、清零远端端口并重置媒体与输出。 */
  public void begin() {
    synchronized (stateLock) {
      active = false;
      epoch++;
      remotePort = 0;
      media.reset();
      sink.reset();
    }
  }

  public void ready(int port) {
    synchronized (stateLock) {
      remotePort = port;
      active = true;
    }
  }

  /** 空实现，不处理格式参数。 */
  public void format(int rate, int channels) {}

  /** 停用接收、递增 epoch 并重置媒体与输出。 */
  public void stop() {
    synchronized (stateLock) {
      active = false;
      epoch++;
      media.reset();
      sink.reset();
    }
  }

  /** 循环接收 UDP 包，校验对端地址与端口，解密解码后按 epoch 校验并提交帧给输出，记录统计并清零敏感数据。 */
  private void receive() {
    byte[] buffer = new byte[65536];
    try {
      while (!closed && events.active()) {
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        try {
          socket.receive(datagram);
        } catch (SocketTimeoutException e) {
          continue;
        }
        if (!active) continue;
        if (!peer.equals(datagram.getAddress().getHostAddress())
            || datagram.getPort() != remotePort) {
          if (++wrongPeer <= 3) events.log("音频 UDP 端点不匹配，未处理该数据包。");
          continue;
        }
        int acceptedEpoch = epoch;
        received++;
        byte[] plain = null;
        try {
          plain = AudioMedia.decrypt(Arrays.copyOf(buffer, datagram.getLength()), cipher);
          synchronized (stateLock) {
            if (!active || epoch != acceptedEpoch) continue;
            for (AudioMedia.Frame frame : media.receive(plain))
              try {
                sink.offer(frame);
              } finally {
                Arrays.fill(frame.data, (byte) 0);
              }
          }
          if (received <= 3 || received % 200 == 0)
            events.log(
                "音频 UDP：已接收 "
                    + received
                    + " 包，提取 "
                    + media.frames
                    + " 个 AAC 帧，丢包 "
                    + media.lost
                    + "，重复 "
                    + media.duplicates
                    + "。");
        } catch (IOException | GeneralSecurityException e) {
          if (++rejected <= 3) events.log("音频数据包拒绝：" + e.getClass().getSimpleName() + "；未记录媒体内容。");
        } finally {
          if (plain != null) Arrays.fill(plain, (byte) 0);
        }
      }
    } catch (IOException e) {
      if (!closed && events.active()) events.log("音频 UDP 接收结束：" + e.getClass().getSimpleName());
    } finally {
      close();
    }
  }

  /** 关闭套接字与密码，停用接收，重置媒体并关闭输出。 */
  public synchronized void close() {
    if (closed) return;
    closed = true;
    active = false;
    socket.close();
    cipher.close();
    synchronized (stateLock) {
      epoch++;
      media.reset();
      sink.close();
    }
  }
}
