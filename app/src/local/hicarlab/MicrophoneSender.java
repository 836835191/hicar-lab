package local.hicarlab;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.Arrays;

/** Bounded live capture -> AAC -> authenticated phone UDP; never writes audio files. */
final class MicrophoneSender
    implements MicrophoneControl.Output, DeviceBusiness.Speaker, AutoCloseable {
  interface Events {
    boolean active();

    void log(String text);
  }

  private final Context context;
  private final Events events;
  private final DatagramSocket socket;
  private final InetAddress peer;
  private final SessionCipher cipher;
  private volatile boolean closed, recording;
  private volatile int generation;
  private volatile AudioRecord recorder;
  private Thread worker;
  private volatile String status = "麦克风待命";
  private long peerUs, peerNanos;

  /** 初始化应用上下文、事件、目标地址、UDP socket 与会话加密器。 */
  MicrophoneSender(Context c, String owner, String peer, byte[] key, Events events)
      throws IOException {
    context = c.getApplicationContext();
    this.events = events;
    this.peer = InetAddress.getByName(peer);
    socket = new DatagramSocket(new InetSocketAddress(owner, 0));
    cipher = new SessionCipher(key, 65536);
  }

  /** 静态检查UI麦克风开关已开启且已授予录音权限。 */
  static boolean enabled(Context c) {
    return c.getSharedPreferences("ui", 0).getBoolean("microphone", false)
        && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
  }

  /** 返回未关闭、事件处于活动状态且麦克风已启用。 */
  public boolean allowed() {
    return !closed && events.active() && enabled(context);
  }

  /** 返回allowed()的结果。 */
  public boolean ready() {
    return allowed();
  }

  /** 返回UDP socket绑定的本地端口。 */
  public int port() {
    return socket.getLocalPort();
  }

  /** 返回当前麦克风状态字符串。 */
  String status() {
    return status;
  }

  /** 用对端微秒时间与本地nanoTime校准并保存对端时钟基准。 */
  synchronized void clock(long high, long low) {
    long value = ((high & 0xffffffffL) << 32) | (low & 0xffffffffL);
    if (value > 0) {
      peerUs = value;
      peerNanos = System.nanoTime();
    }
  }

  /** 按本地流逝时间推算当前对端微秒时间。 */
  private synchronized long nowPeer() {
    return peerUs > 0 ? peerUs + (System.nanoTime() - peerNanos) / 1000 : System.nanoTime() / 1000;
  }

  /** 将文本转发到事件日志。 */
  public void log(String text) {
    events.log(text);
  }

  /** 校验启用状态与端口后停止旧任务并启动向远端发送的采集线程。 */
  public synchronized void start(int remote) throws IOException {
    if (!allowed() || remote < 1 || remote > 65535)
      throw new IOException("microphone disabled or endpoint");
    stop();
    final int token = ++generation;
    worker = new Thread(() -> capture(token, remote), "lab-microphone");
    worker.start();
  }

  /** 采集PCM并编码为AAC，封装RTP加密后经UDP发送，处理静音检测、超时与资源清理。 */
  private void capture(int token, int remote) {
    AudioRecord record = null;
    MediaCodec encoder = null;
    short[] pcm = new short[1024];
    byte[] raw = null;
    try {
      if (!allowed() || generation != token) return;
      int min =
          AudioRecord.getMinBufferSize(
              48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (min <= 0) throw new IOException("microphone format unavailable");
      record =
          new AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              48000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min * 2, 8192));
      recorder = record;
      if (record.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IOException("microphone initialization");
      encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
      MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
      format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
      format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      encoder.start();
      if (!allowed() || generation != token) return;
      record.startRecording();
      if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("microphone capture refused");
      if (!allowed() || generation != token) return;
      recording = true;
      status = "麦克风正在采集";
      events.log("麦克风开始采集：单声道 PCM 转 AAC/48000 Hz/双声道，仅发送到当前认证手机。");
      long origin = nowPeer(),
          samples = 0,
          packets = 0,
          lastEncoded = System.nanoTime(),
          lastConfig = 0;
      boolean silenced = false;
      int filled = 0, sequence = 0;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (generation == token && allowed()) {
        if (android.os.Build.VERSION.SDK_INT >= 29 && System.nanoTime() - lastConfig > 500000000L) {
          lastConfig = System.nanoTime();
          AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
          boolean muted = config != null && config.isClientSilenced();
          if (muted != silenced) {
            silenced = muted;
            status = muted ? "麦克风被系统静音，请检查其他应用通话或录音" : "麦克风正在采集";
            events.log(muted ? "系统报告本应用录音被静音；未读取其他应用录音信息。" : "系统已恢复本应用麦克风输入。");
          }
        }
        int n = record.read(pcm, filled, pcm.length - filled, AudioRecord.READ_BLOCKING);
        if (n <= 0) throw new IOException("microphone read");
        filled += n;
        if (filled < pcm.length) continue;
        int index = encoder.dequeueInputBuffer(10000);
        if (index >= 0) {
          ByteBuffer input = encoder.getInputBuffer(index);
          if (input == null || input.capacity() < 4096)
            throw new IOException("microphone encoder input");
          input.clear();
          input.order(ByteOrder.LITTLE_ENDIAN);
          for (short value : pcm) {
            input.putShort(value);
            input.putShort(value);
          }
          encoder.queueInputBuffer(index, 0, 4096, samples * 1000000L / 48000, 0);
        }
        samples += pcm.length;
        filled = 0;
        Arrays.fill(pcm, (short) 0);
        for (int i = 0; i < 8; i++) {
          int output = encoder.dequeueOutputBuffer(info, 0);
          if (output < 0) break;
          try {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
              ByteBuffer buffer = encoder.getOutputBuffer(output);
              if (buffer == null || info.size > 8184)
                throw new IOException("microphone encoder output");
              raw = new byte[info.size];
              buffer.position(info.offset);
              buffer.limit(info.offset + info.size);
              buffer.get(raw);
              byte[] rtp =
                  MicrophoneWire.packet(
                      raw,
                      sequence++,
                      MicrophoneWire.timestamp(origin, Math.max(0, info.presentationTimeUs)));
              byte[] encrypted = cipher.encrypt(rtp);
              byte[] packet =
                  NearbyWire.join(
                      new byte[] {
                        (byte) 171, (byte) (encrypted.length >>> 8), (byte) encrypted.length
                      },
                      encrypted);
              try {
                if (generation != token || !allowed()) break;
                socket.send(new DatagramPacket(packet, packet.length, peer, remote));
                packets++;
                lastEncoded = System.nanoTime();
                if (packets == 1 || packets % 200 == 0)
                  events.log("麦克风加密上行：" + packets + " 个 AAC 包；尚需手机识别结果确认。");
              } finally {
                Arrays.fill(raw, (byte) 0);
                raw = null;
                Arrays.fill(rtp, (byte) 0);
                Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(packet, (byte) 0);
              }
            }
          } finally {
            encoder.releaseOutputBuffer(output, false);
          }
        }
        if (System.nanoTime() - lastEncoded > 3000000000L)
          throw new IOException("microphone encoder timeout");
      }
    } catch (Exception e) {
      if (generation == token && allowed()) {
        status = "麦克风暂不可用，请关闭后重新启用";
        events.log("麦克风采集结束：" + e.getClass().getSimpleName() + "；画面及播放继续。");
      }
    } finally {
      Arrays.fill(pcm, (short) 0);
      if (raw != null) Arrays.fill(raw, (byte) 0);
      if (record != null) {
        try {
          record.stop();
        } catch (RuntimeException ignored) {
        }
        record.release();
        if (recorder == record) recorder = null;
      }
      if (encoder != null) {
        try {
          encoder.stop();
        } catch (RuntimeException ignored) {
        }
        encoder.release();
      }
      if (generation == token) {
        recording = false;
        if (!status.startsWith("麦克风暂")) status = enabled(context) ? "麦克风待命" : "麦克风已关闭";
      }
    }
  }

  /** 递增代次停止采集，停止录音、等待工作线程并重置状态。 */
  public synchronized void stop() {
    generation++;
    recording = false;
    AudioRecord current = recorder;
    if (current != null)
      try {
        current.stop();
      } catch (RuntimeException ignored) {
      }
    Thread old = worker;
    worker = null;
    if (old != null && old != Thread.currentThread())
      try {
        old.join(700);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    status = enabled(context) ? "麦克风待命" : "麦克风已关闭";
  }

  /** 标记关闭、停止采集并关闭socket与加密器。 */
  public synchronized void close() {
    if (closed) return;
    closed = true;
    stop();
    socket.close();
    cipher.close();
  }
}
