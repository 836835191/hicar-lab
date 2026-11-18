package local.hicarlab;

/**
 * 当前接收端对外声明的视频与触控分辨率。默认按一加 7 Pro 横屏配置，启动时会根据设备实际屏幕
 * 自动选择一档兼容分辨率，并同步调整码率、触控坐标与显示比例。
 */
final class VideoProfile {
  static volatile int WIDTH = 3120;
  static volatile int HEIGHT = 1440;
  static volatile int BITRATE = 12000000;
  static volatile int BITRATE_MIN = 6000000;
  static volatile int BITRATE_MAX = 18000000;
  static volatile int FPS = 30;
  static volatile int GOP = 30;
  static volatile int DPI = 320;

  private VideoProfile() {}

  /**
   * 根据屏幕实际像素选择分辨率。参数与 Android 无关，便于协议测试在主机上运行。
   *
   * @param widthPx 屏幕宽像素
   * @param heightPx 屏幕高像素
   * @param densityDpi 屏幕密度
   */
  static void detect(int widthPx, int heightPx, int densityDpi) {
    int longSide = Math.max(widthPx, heightPx);
    int shortSide = Math.min(widthPx, heightPx);
    int width, height;
    if (longSide >= 3000 && shortSide >= 1400) {
      width = 3120;
      height = 1440;
    } else if (longSide >= 2500 && shortSide >= 1150) {
      width = 2560;
      height = 1152;
    } else if (longSide >= 1900 && shortSide >= 1050) {
      width = 1920;
      height = 1080;
    } else if (longSide >= 1250 && shortSide >= 700) {
      width = 1280;
      height = 720;
    } else {
      width = 800;
      height = 480;
    }
    WIDTH = width;
    HEIGHT = height;
    DPI = Math.max(120, Math.min(480, densityDpi));
    long pixels = (long) width * height;
    BITRATE = (int) Math.max(2000000L, Math.min(18000000L, pixels * 5L));
    BITRATE_MIN = BITRATE / 2;
    BITRATE_MAX = Math.min(24000000, BITRATE + BITRATE / 2);
  }

  /** 返回当前自适应配置的可读描述。 */
  static String description() {
    return WIDTH + "×" + HEIGHT + "，码率 " + BITRATE / 1000000 + " Mbps";
  }

  /** 返回 HiSight 能力声明所需的视频格式字符串。 */
  static String hisVideoFormats() {
    return "codecs 1;fps " + FPS + ";gop " + GOP + ";bitrate "
        + BITRATE
        + ";vbr-min "
        + BITRATE_MIN
        + ";vbr-max "
        + BITRATE_MAX
        + ";dpi "
        + DPI
        + ";scr-w "
        + WIDTH
        + ";scr-h "
        + HEIGHT
        + ";width "
        + WIDTH
        + ";height "
        + HEIGHT;
  }
}
