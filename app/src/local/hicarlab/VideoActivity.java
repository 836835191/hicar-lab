package local.hicarlab;

import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** User-opened video/input test window. The PIN is never included in logs. */
public final class VideoActivity extends ThemeActivity
    implements TextureView.SurfaceTextureListener {
  private VideoSink sink;
  private InputRouter input;
  private int pointer = -1;
  private TextureView surface;
  private boolean foreground;
  private LinearLayout root, keys;
  private TextView title;
  private Button menu, orientation;
  private boolean fullscreen, landscape, detailsExpanded, receiving;
  private final android.os.Handler uiHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private LinearLayout notice;
  private TextView progress, explanation, micStatus;
  private final Runnable updateStatus =
      new Runnable() {
        /** 定时轮询引擎状态，更新连接信息、麦克风状态文本与提示层可见性。 */
        public void run() {
          ReceiverStatus.Snapshot state =
              ((LabApplication) getApplication()).engine().displayStatus();
          receiving = state.receiving;
          micStatus.setText(((LabApplication) getApplication()).engine().microphoneStatus());
          updateInfoVisibility();
          progress.setText(state.title);
          explanation.setText(state.detail);
          notice.setVisibility(state.receiving ? View.GONE : View.VISIBLE);
          uiHandler.postDelayed(this, 700);
        }
      };

  /** 取消输入并停止引擎，跳转带 retry 标志的 HomeActivity 后结束自身。 */
  private void reconnect() {
    input.cancel();
    pointer = -1;
    ((LabApplication) getApplication()).engine().stop(true);
    startActivity(
        new android.content.Intent(this, HomeActivity.class)
            .putExtra("retry", true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
    finish();
  }

  /** 将 dp 数值换算为像素。 */
  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density + .5f);
  }

  /** 创建功能按钮，点击时先取消输入再执行给定动作并加入按键栏。 */
  private Button key(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(14);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(48));
    b.setOnClickListener(
        v -> {
          input.cancel();
          pointer = -1;
          action.run();
        });
    keys.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    return b;
  }

  /** 构建视频界面（标题、画面、提示层、菜单与功能按钮），恢复状态并应用方向与全屏。 */
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    sink = ((LabApplication) getApplication()).video();
    input = ((LabApplication) getApplication()).input();
    if (state != null) {
      fullscreen = state.getBoolean("fullscreen");
      landscape = state.getBoolean("landscape");
      detailsExpanded = state.getBoolean("detailsExpanded");
    }
    landscape = true;
    fullscreen = true;
    receiving = ((LabApplication) getApplication()).engine().displayStatus().receiving;
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xff000000);
    title = new TextView(this);
    title.setText("HiCar  ·  连接码：" + getSharedPreferences("identity", 0).getString("pin", "未就绪"));
    title.setTextSize(18);
    title.setTextColor(0xffffffff);
    title.setPadding(dp(12), dp(10), dp(12), dp(4));
    root.addView(title);
    micStatus = new TextView(this);
    micStatus.setTextSize(13);
    micStatus.setTextColor(0xffa8caff);
    micStatus.setPadding(dp(12), 0, dp(12), dp(4));
    root.addView(micStatus);
    surface =
        new TextureView(this) {
          /** 匿名 TextureView 的测量回调，按当前视频比例约束视图尺寸。 */
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            if (w * (long) VideoProfile.HEIGHT > h * (long) VideoProfile.WIDTH)
              w = h * VideoProfile.WIDTH / VideoProfile.HEIGHT;
            else h = w * VideoProfile.HEIGHT / VideoProfile.WIDTH;
            setMeasuredDimension(w, h);
          }
        };
    surface.setOnTouchListener((v, event) -> touch(v, event));
    surface.setSurfaceTextureListener(this);
    FrameLayout area = new FrameLayout(this);
    area.setBackgroundColor(0xff000000);
    area.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    notice = new LinearLayout(this);
    notice.setOrientation(LinearLayout.VERTICAL);
    notice.setGravity(Gravity.CENTER);
    notice.setPadding(dp(20), dp(20), dp(20), dp(20));
    notice.setBackgroundColor(0xe6000000);
    notice.setClickable(true);
    progress = new TextView(this);
    progress.setTextSize(22);
    progress.setTextColor(0xffffffff);
    progress.setGravity(Gravity.CENTER);
    notice.addView(progress);
    explanation = new TextView(this);
    explanation.setTextSize(15);
    explanation.setTextColor(0xffdddddd);
    explanation.setGravity(Gravity.CENTER);
    explanation.setPadding(0, dp(10), 0, dp(10));
    notice.addView(explanation);
    Button retry = new Button(this);
    retry.setText("重新连接");
    retry.setOnClickListener(v -> reconnect());
    notice.addView(retry);
    FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
    np.leftMargin = dp(20);
    np.rightMargin = dp(20);
    area.addView(notice, np);
    menu = new Button(this);
    menu.setText("菜单");
    menu.setTextSize(7);
    menu.setMinWidth(0);
    menu.setMinimumWidth(0);
    menu.setMinimumHeight(0);
    menu.setPadding(0, 0, 0, 0);
    FrameLayout.LayoutParams mp =
        new FrameLayout.LayoutParams(dp(38), dp(24), Gravity.TOP | Gravity.END);
    mp.topMargin = dp(3);
    mp.rightMargin = dp(3);
    area.addView(menu, mp);
    menu.setOnClickListener(v -> showMenu());
    root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
    keys = new LinearLayout(this);
    keys.setBackgroundColor(UiTheme.dark(this) ? 0xff202733 : 0xffeeeeee);
    key(
        "返回",
        () -> {
          if (!input.key(4)) Toast.makeText(this, "输入通道尚未就绪", Toast.LENGTH_SHORT).show();
        });
    key(
        "主页",
        () -> {
          if (!input.key(3)) Toast.makeText(this, "输入通道尚未就绪", Toast.LENGTH_SHORT).show();
        });
    orientation =
        key(
            "横屏",
            () -> {
              landscape = !landscape;
              applyOrientation();
            });
    key("全屏", () -> setFullscreen(true));
    key("重连", this::reconnect);
    key(
        "停止",
        () -> {
          ((LabApplication) getApplication()).engine().stop(true);
          finish();
        });
    root.addView(keys);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets edge =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(edge.left, edge.top, edge.right, edge.bottom);
          } else
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(root);
    applyOrientation();
    setFullscreen(fullscreen);
  }

  /** 依据 landscape 更新按钮文案并请求横屏或不限定方向。 */
  private void applyOrientation() {
    orientation.setText(landscape ? "自动旋转" : "横屏");
    setRequestedOrientation(
        landscape
            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
  }

  /** 切换全屏状态，显隐按键栏与系统栏并刷新信息可见性。 */
  private void setFullscreen(boolean enabled) {
    input.cancel();
    pointer = -1;
    fullscreen = enabled;
    updateInfoVisibility();
    keys.setVisibility(enabled ? View.GONE : View.VISIBLE);
    menu.setVisibility(View.VISIBLE);
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController c = getWindow().getInsetsController();
      if (c != null) {
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (enabled) c.hide(WindowInsets.Type.systemBars());
        else c.show(WindowInsets.Type.systemBars());
      }
    } else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              enabled
                  ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                      | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  : View.SYSTEM_UI_FLAG_VISIBLE);
    root.requestApplyInsets();
  }

  /** 依据展开状态与接收、麦克风状态控制标题及麦克风文本显隐。 */
  private void updateInfoVisibility() {
    // The code remains available before connection; routine playback is uncluttered.
    title.setVisibility(detailsExpanded || !receiving ? View.VISIBLE : View.GONE);
    micStatus.setVisibility(
        detailsExpanded && MicrophoneSender.enabled(this) ? View.VISIBLE : View.GONE);
  }

  /** 弹出菜单，提供显示或隐藏连接信息以及退出全屏选项。 */
  private void showMenu() {
    input.cancel();
    pointer = -1;
    PopupMenu popup = new PopupMenu(this, menu);
    popup.getMenu().add(0, 1, 0, detailsExpanded ? "隐藏连接信息" : "显示连接信息");
    if (fullscreen) popup.getMenu().add(0, 2, 1, "退出全屏");
    popup.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == 1) {
            detailsExpanded = !detailsExpanded;
            updateInfoVisibility();
            return true;
          }
          if (item.getItemId() == 2) {
            setFullscreen(false);
            return true;
          }
          return false;
        });
    popup.show();
  }

  /** 全屏时先退出全屏，否则执行默认返回逻辑。 */
  @Override
  public void onBackPressed() {
    if (fullscreen) setFullscreen(false);
    else super.onBackPressed();
  }

  /** 配置变更时取消输入并重新应用全屏设置。 */
  @Override
  public void onConfigurationChanged(android.content.res.Configuration value) {
    super.onConfigurationChanged(value);
    input.cancel();
    pointer = -1;
    root.post(() -> setFullscreen(fullscreen));
  }

  /** 保存全屏、横屏与信息展开状态到 Bundle。 */
  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("fullscreen", fullscreen);
    state.putBoolean("landscape", landscape);
    state.putBoolean("detailsExpanded", detailsExpanded);
    super.onSaveInstanceState(state);
  }

  /** 将触摸动作经 InputRouter 映射为输入事件并发送，管理指针与抬升反馈。 */
  private boolean touch(View view, MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      if (!sink.hasFrame() || !input.ready()) return false;
      int[] pos =
          InputRouter.map(event.getX(), event.getY(), view.getWidth(), view.getHeight(), false);
      if (pos == null || !input.touch(0, pos[0], pos[1])) return false;
      pointer = event.getPointerId(0);
      return true;
    }
    if (pointer < 0) return false;
    if (action == MotionEvent.ACTION_CANCEL) {
      input.cancel();
      pointer = -1;
      return true;
    }
    if (action == MotionEvent.ACTION_POINTER_DOWN) return true;
    if (action == MotionEvent.ACTION_POINTER_UP
        && event.getPointerId(event.getActionIndex()) != pointer) return true;
    int index = event.findPointerIndex(pointer);
    if (index < 0) {
      input.cancel();
      pointer = -1;
      return true;
    }
    if (action == MotionEvent.ACTION_MOVE
        || action == MotionEvent.ACTION_UP
        || action == MotionEvent.ACTION_POINTER_UP) {
      int[] pos =
          InputRouter.map(
              event.getX(index), event.getY(index), view.getWidth(), view.getHeight(), true);
      boolean up = action != MotionEvent.ACTION_MOVE;
      if (pos != null) input.touch(up ? 1 : 2, pos[0], pos[1]);
      else input.cancel();
      if (up) {
        pointer = -1;
        view.performClick();
      }
      return true;
    }
    return true;
  }

  /** 把可用 SurfaceTexture 附着到 VideoSink，失败时停引擎并报告问题。 */
  public void onSurfaceTextureAvailable(
      android.graphics.SurfaceTexture value, int width, int height) {
    try {
      sink.attach(surface, value);
      if (!foreground) sink.hide(surface);
    } catch (RuntimeException e) {
      DiagnosticLog.add(this, "接收窗口附着失败：" + e.getClass().getSimpleName());
      ((LabApplication) getApplication()).engine().stop(true);
      ((LabApplication) getApplication()).engine().reportProblem("画面窗口未能恢复，请重新连接。");
    }
  }

  /** 空实现，表面尺寸变化时不作处理。 */
  public void onSurfaceTextureSizeChanged(
      android.graphics.SurfaceTexture value, int width, int height) {}

  /** 空实现，表面内容更新时不作处理。 */
  public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture value) {}

  /** 取消输入并从 VideoSink 分离显示表面。 */
  public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture value) {
    input.cancel();
    pointer = -1;
    return sink.detach(surface, value);
  }

  /** 标记前台、显示画面并重新应用全屏设置。 */
  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    if (surface != null) sink.show(surface);
    if (root != null) root.post(() -> setFullscreen(fullscreen));
  }

  /** 标记后台、取消输入并隐藏画面。 */
  @Override
  protected void onPause() {
    foreground = false;
    input.cancel();
    pointer = -1;
    if (surface != null) sink.hide(surface);
    super.onPause();
  }

  /** 启动周期性状态轮询任务。 */
  @Override
  protected void onStart() {
    super.onStart();
    uiHandler.removeCallbacks(updateStatus);
    uiHandler.post(updateStatus);
  }

  /** 移除状态轮询任务。 */
  @Override
  protected void onStop() {
    uiHandler.removeCallbacks(updateStatus);
    super.onStop();
  }

  /** 清除全部 Handler 回调并隐藏画面。 */
  @Override
  protected void onDestroy() {
    uiHandler.removeCallbacksAndMessages(null);
    if (surface != null) sink.hide(surface);
    super.onDestroy();
  }
}
