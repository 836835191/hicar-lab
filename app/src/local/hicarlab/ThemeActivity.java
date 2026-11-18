package local.hicarlab;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

/** Theme recreation keeps the application-owned receiver/session alive. */
public class ThemeActivity extends Activity {
  private boolean dark;

  /** 依据 UiTheme 设置明暗主题、状态栏与导航栏颜色及系统栏图标明暗。 */
  @Override
  protected void onCreate(Bundle state) {
    dark = UiTheme.dark(this);
    setTheme(
        dark
            ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
    super.onCreate(state);
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    getWindow().setStatusBarColor(UiTheme.background(this));
    getWindow().setNavigationBarColor(UiTheme.background(this));
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  /** 若当前主题与记录值不一致则重建 Activity。 */
  @Override
  protected void onResume() {
    super.onResume();
    if (dark != UiTheme.dark(this)) recreate();
  }

  /** 配置变更时若主题与记录值不一致则重建 Activity。 */
  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    if (dark != UiTheme.dark(this)) recreate();
  }
}
