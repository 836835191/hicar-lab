package local.hicarlab;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.*;
import android.view.Surface;
import android.view.TextureView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Bounded live decode to a Surface; no recording or frame files. */
final class VideoSink implements AutoCloseable, HiSightClient.Video {
  private final Context context;
  private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(8);
  private volatile boolean ready, running, decoded;
  private Thread worker;
  private MediaCodec codec;
  private Surface surface;
  private SurfaceTexture texture;
  private java.lang.ref.WeakReference<TextureView> owner = new java.lang.ref.WeakReference<>(null);
  private volatile boolean visible;
  private long received, dropped;
  private volatile long rendered;
  private volatile int generation;

  private static final class Frame {
    byte[] data;
    long pts;

    /** 构造函数，保存帧字节数据与时间戳。 */
    Frame(byte[] d, long t) {
      data = d;
      pts = t;
    }
  }

  /** 构造函数，保存 Application 上下文。 */
  VideoSink(Context c) {
    context = c.getApplicationContext();
  }

  /** 返回解码器是否已就绪。 */
  public boolean ready() {
    return ready;
  }

  /** 返回是否就绪且已成功解码出画面帧。 */
  boolean hasDecoded() {
    return ready && decoded;
  }

  /** 返回是否就绪、可见且已有渲染帧。 */
  boolean hasFrame() {
    return ready && visible && rendered > 0;
  }

  /** 准备后台解码，复用有效表面或新建独立 SurfaceTexture 后启动解码器。 */
  synchronized boolean prepareBackground() {
    if (ready) return true;
    if (surface != null && surface.isValid()) {
      startDecoder();
      return ready;
    }
    close();
    // Detached SurfaceTexture requires no Activity or current GL context.
    texture = new SurfaceTexture(false);
    texture.setDefaultBufferSize(VideoProfile.WIDTH, VideoProfile.HEIGHT);
    surface = new Surface(texture);
    visible = false;
    startDecoder();
    if (!ready) close();
    return ready;
  }

  /** 将 TextureView 表面附着到解码输出，复用或新建纹理并启动解码器。 */
  synchronized void attach(TextureView view, SurfaceTexture available) {
    if (texture != null && surface != null && surface.isValid()) {
      if (view.getSurfaceTexture() != texture) view.setSurfaceTexture(texture);
      owner = new java.lang.ref.WeakReference<>(view);
      visible = true;
      rendered = 0;
      if (!ready) startDecoder();
      DiagnosticLog.add(context, "接收窗口已重新附着；沿用当前视频会话，等待新画面。");
      return;
    }
    close();
    texture = available;
    texture.setDefaultBufferSize(VideoProfile.WIDTH, VideoProfile.HEIGHT);
    surface = new Surface(texture);
    owner = new java.lang.ref.WeakReference<>(view);
    visible = true;
    startDecoder();
  }

  /** 分离显示窗口时标记不可见并保留纹理与会话，返回 false 表示继续持有纹理。 */
  synchronized boolean detach(TextureView view, SurfaceTexture value) {
    if (value != texture) return true;
    if (owner.get() == view) {
      visible = false;
      rendered = 0;
      owner.clear();
      DiagnosticLog.add(context, "显示窗口已分离，保留视频会话与音频；后台解码输出丢弃，不渲染。");
    }
    return false; // Own the texture until explicit engine stop; TextureView must not release it.
  }

  /** 若为该视图则标记不可见并清零渲染计数。 */
  synchronized void hide(TextureView view) {
    if (owner.get() == view) {
      visible = false;
      rendered = 0;
    }
  }

  /** 若为该可用视图则标记可见并清零渲染计数。 */
  synchronized void show(TextureView view) {
    if (owner.get() == view && view.isAvailable()) {
      visible = true;
      rendered = 0;
    }
  }

  /** 创建配置 H.264 解码器并启动解码线程，失败时释放资源并记录日志。 */
  private void startDecoder() {
    releaseDecoder();
    try {
      codec = MediaCodec.createDecoderByType("video/avc");
      MediaFormat format =
          MediaFormat.createVideoFormat("video/avc", VideoProfile.WIDTH, VideoProfile.HEIGHT);
      format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, HiSightMedia.LIMIT);
      format.setInteger("low-latency", 1);
      codec.configure(format, surface, null, 0);
      codec.start();
      running = true;
      ready = true;
      received = rendered = dropped = 0;
      final MediaCodec prepared = codec;
      final int token = generation;
      worker = new Thread(() -> decode(prepared, token), "lab-video-decoder");
      worker.start();
      DiagnosticLog.add(context, "H.264 解码器与视频窗口已就绪；尚未收到或显示手机视频。");
    } catch (IOException | RuntimeException e) {
      ready = false;
      running = false;
      if (codec != null) {
        try {
          codec.release();
        } catch (RuntimeException ignored) {
        }
        codec = null;
      }
      DiagnosticLog.add(context, "视频解码器启动失败：" + e.getClass().getSimpleName());
    }
  }

  /** 接收 kind 为 96 的 H.264 帧并拷贝入有界队列，队列满则丢弃并计数。 */
  public void offer(int kind, long timestamp, byte[] data) throws IOException {
    if (kind != 96) return;
    if (!ready || data.length == 0 || data.length > HiSightMedia.LIMIT)
      throw new IOException("video sink unavailable");
    Frame frame = new Frame(data.clone(), timestamp * 1000000L / 90000L);
    if (!frames.offer(frame)) {
      Arrays.fill(frame.data, (byte) 0);
      dropped++;
    }
    received++;
    if (received <= 3 || received % 60 == 0)
      DiagnosticLog.add(
          context, "收到 H.264 数据帧 " + received + "，长度 " + data.length + " 字节；尚不能仅据此确认画面。");
  }

  /** 判断帧数据是否仅含 SPS/PPS 配置而不含视频片。 */
  private static boolean onlyConfig(byte[] data) {
    boolean config = false;
    for (int i = 0; i + 4 < data.length; i++)
      if (data[i] == 0
          && data[i + 1] == 0
          && (data[i + 2] == 1 || data[i + 2] == 0 && data[i + 3] == 1)) {
        int at = i + (data[i + 2] == 1 ? 3 : 4);
        if (at >= data.length) break;
        int type = data[at] & 31;
        if (type >= 1 && type <= 5) return false;
        if (type == 7 || type == 8) config = true;
      }
    return config;
  }

  /** 解码线程主循环，送帧入解码器并按可见性提交输出，记录统计与格式信息。 */
  private void decode(MediaCodec decoder, int token) {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    Frame pending = null;
    try {
      while (running && generation == token) {
        if (pending == null) pending = frames.poll();
        if (pending != null) {
          int input = decoder.dequeueInputBuffer(5000);
          if (input >= 0) {
            ByteBuffer buffer = decoder.getInputBuffer(input);
            if (buffer == null || buffer.capacity() < pending.data.length)
              throw new IOException("video input capacity");
            buffer.clear();
            buffer.put(pending.data);
            int flags = onlyConfig(pending.data) ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
            decoder.queueInputBuffer(input, 0, pending.data.length, pending.pts, flags);
            Arrays.fill(pending.data, (byte) 0);
            pending = null;
          }
        }
        int output = decoder.dequeueOutputBuffer(info, 5000);
        if (output >= 0) {
          if ((info.flags
                  & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_END_OF_STREAM))
              == 0) decoded = true;
          boolean show =
              visible
                  && (info.flags
                          & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                              | MediaCodec.BUFFER_FLAG_END_OF_STREAM))
                      == 0;
          decoder.releaseOutputBuffer(output, show);
          if (show) {
            rendered++;
            if (rendered <= 3 || rendered % 60 == 0)
              DiagnosticLog.add(context, "解码输出已提交视频窗口：" + rendered + " 帧；请以实际画面确认。");
          }
        } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat actual = decoder.getOutputFormat();
          DiagnosticLog.add(
              context,
              "视频输出格式："
                  + actual.getInteger(MediaFormat.KEY_WIDTH)
                  + "×"
                  + actual.getInteger(MediaFormat.KEY_HEIGHT)
                  + "。");
        }
      }
    } catch (IOException | RuntimeException e) {
      if (generation == token) {
        ready = false;
        running = false;
        DiagnosticLog.add(context, "视频解码停止：" + e.getClass().getSimpleName());
      }
    } finally {
      if (pending != null) Arrays.fill(pending.data, (byte) 0);
    }
  }

  /** 当前表面有效时重新创建并启动解码器。 */
  synchronized boolean restart() {
    Surface current = surface;
    if (current == null || !current.isValid()) return false;
    startDecoder();
    return ready;
  }

  /** 递增代次并停止解码线程、释放解码器及清空队列中的帧。 */
  private void releaseDecoder() {
    generation++;
    ready = false;
    running = false;
    decoded = false;
    if (worker != null) {
      try {
        worker.join(300);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      worker = null;
    }
    if (codec != null) {
      try {
        codec.stop();
      } catch (RuntimeException ignored) {
      }
      try {
        codec.release();
      } catch (RuntimeException ignored) {
      }
      codec = null;
    }
    Frame f;
    while ((f = frames.poll()) != null) Arrays.fill(f.data, (byte) 0);
  }

  /** 释放解码器、表面与纹理并清空视图弱引用。 */
  public synchronized void close() {
    visible = false;
    releaseDecoder();
    owner.clear();
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (texture != null) {
      texture.release();
      texture = null;
    }
  }
}
