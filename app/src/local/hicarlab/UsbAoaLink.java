package local.hicarlab;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Experimental USB AOA accessory transport. It only detects an accessory, asks for permission
 * and exposes the bulk streams; the HiCar protocol over AOA is not implemented yet.
 */
final class UsbAoaLink implements AutoCloseable {
  interface Sink {
    /** Called with raw bytes read from the AOA bulk endpoint. */
    void data(byte[] buffer, int length);
  }

  private static final String ACTION_PERMISSION = "local.hicarlab.USB_AOA_PERMISSION";

  private final Context context;
  private final UsbManager manager;
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
          String action = intent == null ? null : intent.getAction();
          if (ACTION_PERMISSION.equals(action)) {
            if (accessory != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
              open();
            else state = "权限被拒绝";
            return;
          }
          if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
            accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (accessory == null) return;
            state = "检测到 AOA 配件，等待权限";
            if (manager.hasPermission(accessory)) open();
            else
              manager.requestPermission(
                  accessory, PendingIntent.getBroadcast(context, 0, new Intent(ACTION_PERMISSION).setPackage(context.getPackageName()), flags()));
            return;
          }
          if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) closeStreams();
        }
      };

  private volatile String state = "未检测到 USB AOA 配件";
  private volatile UsbAccessory accessory;
  private volatile FileInputStream input;
  private volatile FileOutputStream output;
  private volatile Thread reader;
  private volatile Sink sink;
  private volatile long received;
  private volatile long sent;
  private boolean registered;

  UsbAoaLink(Context context) {
    this.context = context.getApplicationContext();
    manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
  }

  /** 开始监听 AOA 配件插入与权限结果。 */
  synchronized void start(Sink sink) {
    this.sink = sink;
    if (registered || manager == null) {
      if (manager == null) state = "系统未提供 UsbManager";
      return;
    }
    IntentFilter filter = new IntentFilter();
    filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
    filter.addAction(ACTION_PERMISSION);
    context.registerReceiver(receiver, filter);
    registered = true;
    state = "已启动 AOA 监听；等待手机作为 USB 主机发起配件模式";
  }

  /** 返回诊断页显示的状态摘要，不包含任何载荷内容。 */
  String status() {
    String device = "";
    UsbAccessory current = accessory;
    if (current != null) device = "（" + current.getManufacturer() + "/" + current.getModel() + "）";
    return "USB AOA（实验骨架）：" + state + device + "；收到 " + received + " 字节，发出 " + sent + " 字节。";
  }

  /** 向 AOA bulk 输出端点写入原始字节；未连接时返回 false。 */
  synchronized boolean write(byte[] data) {
    FileOutputStream stream = output;
    if (stream == null || data == null || data.length == 0) return false;
    try {
      stream.write(data);
      stream.flush();
      sent += data.length;
      return true;
    } catch (IOException e) {
      state = "AOA 写入失败：" + e.getClass().getSimpleName();
      closeStreams();
      return false;
    }
  }

  private void open() {
    try {
      UsbAccessory current = accessory;
      if (current == null) return;
      android.os.ParcelFileDescriptor descriptor = manager.openAccessory(current);
      if (descriptor == null) {
        state = "无法打开 AOA 配件";
        return;
      }
      input = new FileInputStream(descriptor.getFileDescriptor());
      output = new FileOutputStream(descriptor.getFileDescriptor());
      state = "AOA bulk 流已打开；协议尚未实现";
      Thread worker =
          new Thread(
              () -> {
                byte[] buffer = new byte[16384];
                while (input != null) {
                  try {
                    int n = input.read(buffer);
                    if (n < 0) break;
                    received += n;
                    Sink target = sink;
                    if (target != null) target.data(buffer, n);
                  } catch (IOException e) {
                    break;
                  }
                }
                closeStreams();
              },
              "lab-aoa-rx");
      reader = worker;
      worker.start();
    } catch (RuntimeException e) {
      state = "打开 AOA 异常：" + e.getClass().getSimpleName();
    }
  }

  private synchronized void closeStreams() {
    try {
      if (input != null) input.close();
    } catch (IOException ignored) {
    }
    try {
      if (output != null) output.close();
    } catch (IOException ignored) {
    }
    input = null;
    output = null;
    reader = null;
    state = accessory == null ? "未检测到 USB AOA 配件" : "AOA 连接已断开";
  }

  private static int flags() {
    return Build.VERSION.SDK_INT >= 31
        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        : PendingIntent.FLAG_UPDATE_CURRENT;
  }

  @Override
  public synchronized void close() {
    if (registered) {
      try {
        context.unregisterReceiver(receiver);
      } catch (IllegalArgumentException ignored) {
      }
      registered = false;
    }
    closeStreams();
    sink = null;
  }
}
