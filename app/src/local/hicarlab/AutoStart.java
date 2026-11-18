package local.hicarlab;

import android.Manifest;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

/** Opt-in boot reception for an already configured receiver; never enables a radio silently. */
final class AutoStart {
  /** 从 ui 共享偏好读取 auto_start 开关值。 */
  static boolean enabled(Context c) {
    return c.getSharedPreferences("ui", 0).getBoolean("auto_start", true);
  }

  /** 依次检查蓝牙、无线权限及已保存的本机身份格式，返回首个阻碍自动启动的提示，条件齐备时返回 null。 */
  static String problem(Context c) {
    if (Build.VERSION.SDK_INT >= 31)
      for (String p :
          new String[] {
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
          })
        if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
          return "请先手动连接一次，允许蓝牙及附近设备权限。";
    String wifi =
        Build.VERSION.SDK_INT >= 33
            ? "android.permission.NEARBY_WIFI_DEVICES"
            : Manifest.permission.ACCESS_FINE_LOCATION;
    if (c.checkSelfPermission(wifi) != PackageManager.PERMISSION_GRANTED)
      return "请先手动连接一次，允许无线连接权限。";
    String wire = c.getSharedPreferences("identity", 0).getString("wire_id", "");
    String model = c.getSharedPreferences("identity", 0).getString("wire_model", "");
    if (wire == null
        || !wire.matches("[0-9A-Fa-f]{64}")
        || model == null
        || !model.matches("[0-9A-Fa-f]{8}")) return "请先在设置与诊断中保存本机蓝牙身份，并完成一次手机连接。";
    return null;
  }

  /** 检查蓝牙适配器是否存在且已开启，异常时返回 false。 */
  static boolean bluetoothReady(Context c) {
    try {
      BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
      BluetoothAdapter a = m == null ? null : m.getAdapter();
      return a != null && a.isEnabled();
    } catch (RuntimeException e) {
      return false;
    }
  }
}
