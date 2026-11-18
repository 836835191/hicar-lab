package local.hicarlab;

/** Synchronous, process-global HUKS runtime; callers serialize native use. */
final class NativeAuth {
  static {
    System.loadLibrary("carlink_hichain");
  }

  /** 原生方法：用路径与硬件种子初始化 HUKS 运行时并返回状态码。 */
  static synchronized native int init(String path, String hardwareSeed);

  /** 原生方法：按本机、对端、PIN 与角色创建认证会话并返回句柄。 */
  static synchronized native long create(String self, String peer, String pin, int role);

  /** 原生方法：处理一次认证步进输入包并返回响应包数组。 */
  static synchronized native byte[][] step(long handle, byte[] packet, int keyLength);

  /** 原生方法：从会话句柄导出会话密钥。 */
  static synchronized native byte[] sessionKey(long handle);

  /** 原生方法：关闭并释放认证会话句柄。 */
  static synchronized native void close(long handle);
}
