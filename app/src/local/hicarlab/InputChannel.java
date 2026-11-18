package local.hicarlab;

import java.io.*;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.*;

/** Bounded background sender; no network I/O on the UI thread. */
final class InputChannel implements InputRouter.Sender, AutoCloseable {
  private final Socket socket;
  private final InputRouter router;
  private final UiInput encoder;
  private final HiSightClient.Events events;
  private final ArrayBlockingQueue<UiInput.Event> pending = new ArrayBlockingQueue<>(64);
  private volatile boolean closed;
  private final long start = System.nanoTime();

  /** 保存socket、路由器与事件回调用密钥创建UiInput编码器。 */
  InputChannel(Socket s, byte[] key, InputRouter r, HiSightClient.Events e) {
    socket = s;
    encoder = new UiInput(key);
    router = r;
    events = e;
  }

  /** 把本通道注册到路由器并启动后台写线程。 */
  void start() {
    router.attach(this);
    new Thread(this::write, "lab-uibc-writer").start();
  }

  /** 把输入事件放入有界队列，移动事件满时丢弃、按下抬起事件满时关闭通道。 */
  public boolean send(UiInput.Event event) {
    if (closed || !events.active()) return false;
    if (pending.offer(event)) return true;
    // Moves may be dropped; never silently lose a down/up pair.
    if (event.action == 2) return true;
    close();
    events.log("输入队列已满，关闭输入通道，避免保留按下状态。");
    return false;
  }

  /** 后台循环取事件编码加密后写入socket输出流并定期记日志。 */
  private void write() {
    long count = 0;
    try {
      OutputStream out = socket.getOutputStream();
      while (!closed && events.active()) {
        UiInput.Event event = pending.poll(250, TimeUnit.MILLISECONDS);
        if (event == null) continue;
        byte[] packet = encoder.encode(event, (System.nanoTime() - start) / 1000000L);
        out.write(packet);
        out.flush();
        count++;
        if (count <= 3 || count % 30 == 0) events.log("已发送加密输入事件 " + count + "；不记录坐标，是否生效请看画面。");
      }
    } catch (IOException | GeneralSecurityException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      if (!closed && events.active()) events.log("输入发送停止：" + e.getClass().getSimpleName());
    } finally {
      close();
    }
  }

  /** 从路由器注销、清空队列并关闭socket与编码器。 */
  public void close() {
    if (closed) return;
    closed = true;
    router.detach(this);
    pending.clear();
    try {
      socket.close();
    } catch (IOException ignored) {
    }
    encoder.close();
  }
}
