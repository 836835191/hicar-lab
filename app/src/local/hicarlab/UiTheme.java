package local.hicarlab;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;

final class UiTheme {
  /** 从 ui 偏好设置读取主题模式整数值。 */
  static int mode(Context c) {
    return c.getSharedPreferences("ui", 0).getInt("theme", 0);
  }

  /** 依据模式及系统夜间设置判断是否使用深色主题。 */
  static boolean dark(Context c) {
    int m = mode(c);
    return m == 2
        || m == 0
            && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
  }

  /** 按明暗返回背景色值。 */
  static int background(Context c) {
    return dark(c) ? 0xff11151c : 0xfff4f6fa;
  }

  /** 按明暗返回卡片背景色值。 */
  static int card(Context c) {
    return dark(c) ? 0xff202733 : 0xffffffff;
  }

  /** 按明暗返回文字颜色值。 */
  static int text(Context c) {
    return dark(c) ? 0xffedf2fa : 0xff182438;
  }

  /** 按明暗返回强调色值。 */
  static int accent(Context c) {
    return dark(c) ? 0xffa8caff : 0xff183d74;
  }

  /** 返回当前模式对应的中文标签（跟随系统/浅色/深色）。 */
  static String label(Context c) {
    return new String[] {"跟随系统", "浅色", "深色"}[Math.max(0, Math.min(2, mode(c)))];
  }

  /** 弹出外观模式单选对话框，选中后保存偏好并重建 Activity。 */
  static void choose(Activity a) {
    new AlertDialog.Builder(a)
        .setTitle("外观模式")
        .setSingleChoiceItems(
            new String[] {"跟随系统", "浅色", "深色"},
            mode(a),
            (dialog, which) -> {
              a.getSharedPreferences("ui", 0).edit().putInt("theme", which).apply();
              dialog.dismiss();
              a.recreate();
            })
        .setNegativeButton("取消", null)
        .show();
  }
}
