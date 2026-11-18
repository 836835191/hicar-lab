package local.hicarlab;

import java.io.*;
import java.net.*;
import java.util.*;

/** Read only the already created GO interface using ordinary app privileges. */
final class OwnGroupAddress {
  /** 判断字符串是否为格式合法、非全零与非 02 开头且最低位为 0 的单播 MAC 地址。 */
  static boolean valid(String s) {
    return s != null
        && s.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
        && !s.equalsIgnoreCase("02:00:00:00:00:00")
        && !s.equals("00:00:00:00:00:00")
        && (Integer.parseInt(s.substring(0, 2), 16) & 1) == 0;
  }

  /** 将 6 字节数组格式化为大写冒号分隔的 MAC 字符串，仅校验通过时返回否则返回 null。 */
  static String format(byte[] bytes) {
    if (bytes == null || bytes.length != 6) return null;
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) {
      if (out.length() > 0) out.append(':');
      out.append(String.format(Locale.ROOT, "%02X", b & 255));
    }
    return valid(out.toString()) ? out.toString() : null;
  }

  /** 校验网卡名后尝试从接口硬件地址与 /sys 地址文件读取 MAC，两者一致或仅一者有效时返回并通过事件记录结果。 */
  static String read(NetworkInterface network, String name, P2pHotspot.Events events) {
    if (network == null
        || name == null
        || !name.matches("[A-Za-z0-9_.:-]{1,32}")
        || !name.equals(network.getName())) return null;
    String hardware = null, file = null;
    try {
      hardware = format(network.getHardwareAddress());
      events.log("本轮热点接口公开地址读取：" + (hardware != null ? "取得有效值（不写日志）" : "未提供有效值"));
    } catch (Exception e) {
      events.log("本轮热点接口公开地址读取未获支持：" + e.getClass().getSimpleName());
    }
    try (BufferedReader reader =
        new BufferedReader(new FileReader("/sys/class/net/" + name + "/address"))) {
      char[] chars = new char[19];
      int n = reader.read(chars);
      String value = n > 0 ? new String(chars, 0, n).trim() : "";
      Arrays.fill(chars, '\0');
      if (valid(value)) file = value.toUpperCase(Locale.ROOT);
      events.log("本轮热点接口普通文件读取：" + (file != null ? "取得有效值（不写日志）" : "未提供有效值"));
    } catch (Exception e) {
      events.log("本轮热点接口普通文件读取未获支持：" + e.getClass().getSimpleName());
    }
    if (hardware != null && file != null && !hardware.equals(file)) {
      events.log("本轮热点接口地址来源不一致，未采用。");
      return null;
    }
    return hardware != null ? hardware : file;
  }
}
