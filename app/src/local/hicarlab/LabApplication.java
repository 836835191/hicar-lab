package local.hicarlab;

import android.app.Application;

public final class LabApplication extends Application {
  private ReceiverEngine engine;
  private VideoSink video;
  private UsbAoaLink usbAoa;
  private final InputRouter input = new InputRouter();

  /** 返回全局唯一的InputRouter实例。 */
  InputRouter input() {
    return input;
  }

  /** 懒加载并返回全局VideoSink实例。 */
  synchronized VideoSink video() {
    if (video == null) video = new VideoSink(this);
    return video;
  }

  /** 懒加载并返回全局ReceiverEngine实例。 */
  synchronized ReceiverEngine engine() {
    if (engine == null) engine = new ReceiverEngine(this);
    return engine;
  }

  /** 懒加载并返回全局 USB AOA 实验链路。 */
  synchronized UsbAoaLink usbAoa() {
    if (usbAoa == null) usbAoa = new UsbAoaLink(this);
    return usbAoa;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    usbAoa().start((buffer, length) -> DiagnosticLog.add(this, "AOA 收到 " + length + " 字节；协议未解析。"));
    android.util.Log.d("HiCarLabAoa", usbAoa().status());
  }
}
