package local.hicarlab;

/** Only the current authenticated input session can receive UI events. */
final class InputRouter {
  interface Sender {
    /** 定义发送输入事件的接口方法。 */
    boolean send(UiInput.Event event);
  }

  private Sender sender;
  private boolean pressed;
  private int x, y;

  /** 设置当前发送端并重置按下状态。 */
  synchronized void attach(Sender next) {
    sender = next;
    pressed = false;
  }

  /** 传入对象为当前发送端时清空并重置按下状态。 */
  synchronized void detach(Sender old) {
    if (sender == old) {
      sender = null;
      pressed = false;
    }
  }

  /** 返回是否已绑定发送端。 */
  synchronized boolean ready() {
    return sender != null;
  }

  /** 校验坐标与动作顺序后发送触摸事件并更新位置与按下状态。 */
  synchronized boolean touch(int action, int nx, int ny) {
    if (sender == null
        || nx < 0
        || nx >= VideoProfile.WIDTH
        || ny < 0
        || ny >= VideoProfile.HEIGHT
        || action < 0
        || action > 2) return false;
    if (action == 0 && pressed || action != 0 && !pressed) return false;
    if (!sender.send(UiInput.Event.touch(action, nx, ny))) return false;
    x = nx;
    y = ny;
    pressed = action != 1;
    return true;
  }

  /** 按下状态时补发一次抬起事件并清除按下状态。 */
  synchronized void cancel() {
    if (pressed && sender != null) sender.send(UiInput.Event.touch(1, x, y));
    pressed = false;
  }

  /** 取消触摸后对允许按键发送按下与抬起一对事件。 */
  synchronized boolean key(int key) {
    if (sender == null || (key != 3 && key != 4)) return false;
    cancel();
    if (!sender.send(UiInput.Event.key(key, true))) return false;
    return sender.send(UiInput.Event.key(key, false));
  }

  /** 把坐标映射到当前视频分辨率范围，宽高非法或越界不裁剪时返回空。 */
  static int[] map(float x, float y, int width, int height, boolean clamp) {
    if (width < 1
        || height < 1
        || Float.isNaN(x)
        || Float.isInfinite(x)
        || Float.isNaN(y)
        || Float.isInfinite(y)) return null;
    if (!clamp && (x < 0 || x >= width || y < 0 || y >= height)) return null;
    int px =
        Math.max(0, Math.min(VideoProfile.WIDTH - 1, (int) Math.floor(x * VideoProfile.WIDTH / (double) width)));
    int py =
        Math.max(
            0, Math.min(VideoProfile.HEIGHT - 1, (int) Math.floor(y * VideoProfile.HEIGHT / (double) height)));
    return new int[] {px, py};
  }
}
