package local.hicarlab;

/** Public UI state contains local fixed hints only, never protocol identities or packets. */
final class ReceiverStatus {
  enum Stage {
    STOPPED,
    PREPARING,
    WAITING,
    VERIFYING,
    NETWORK,
    MEDIA,
    RETRYING,
    FAILED
  }

  static final class Snapshot {
    final String title, detail;
    final boolean problem, receiving;

    /** 构造不含接收中标记的状态快照，receiving 为 false。 */
    Snapshot(String title, String detail, boolean problem) {
      this(title, detail, problem, false);
    }

    Snapshot(String title, String detail, boolean problem, boolean receiving) {
      this.title = title;
      this.detail = detail;
      this.problem = problem;
      this.receiving = receiving;
    }
  }

  private int generation;
  private Stage stage = Stage.STOPPED;
  private String issue = "";

  /** 以新 generation 开始一轮连接，状态置为 PREPARING 并清空提示。 */
  synchronized void begin(int token) {
    generation = token;
    stage = Stage.PREPARING;
    issue = "";
  }

  /** 仅当 generation 匹配且状态可推进时更新到 PREPARING 至 MEDIA 范围内的新阶段并清空提示。 */
  synchronized void advance(int token, Stage next) {
    if (token != generation
        || stage == Stage.STOPPED
        || stage == Stage.FAILED
        || stage == Stage.RETRYING) return;
    if (next.ordinal() < Stage.PREPARING.ordinal() || next.ordinal() > Stage.MEDIA.ordinal())
      return;
    if (next.ordinal() >= stage.ordinal()) {
      stage = next;
      issue = "";
    }
  }

  /** 仅当 generation 匹配且状态未停止与未失败时记录提示文本。 */
  synchronized void issue(int token, String hint) {
    if (token == generation && stage != Stage.STOPPED && stage != Stage.FAILED) issue = hint;
  }

  /** 以新 generation 将状态置为 STOPPED 并清空提示。 */
  synchronized void stopped(int token) {
    generation = token;
    stage = Stage.STOPPED;
    issue = "";
  }

  /** 以新 generation 将状态置为 FAILED 并记录提示。 */
  synchronized void failed(int token, String hint) {
    generation = token;
    stage = Stage.FAILED;
    issue = hint;
  }

  /** 以新 generation 将状态置为 RETRYING 并设置重连提示。 */
  synchronized void retrying(int token) {
    generation = token;
    stage = Stage.RETRYING;
    issue = "连接已中断，正在重新准备；也可手动重新连接。";
  }

  /** 根据阶段、问题提示和画面是否解码生成面向 UI 的状态快照。 */
  synchronized Snapshot snapshot(boolean mediaDecoded) {
    if (stage == Stage.FAILED) return new Snapshot("连接未完成", issue, true);
    if (stage == Stage.STOPPED) return new Snapshot("已停止", "点击开始连接，等待手机上的 HiCar 提示。", false);
    if (stage == Stage.RETRYING) return new Snapshot("正在重新连接", issue, true);
    if (mediaDecoded) return new Snapshot("正在接收", "", false, true);
    String title =
        stage == Stage.PREPARING
            ? "正在准备连接"
            : stage == Stage.WAITING
                ? "等待手机连接"
                : stage == Stage.VERIFYING
                    ? "正在验证连接"
                    : stage == Stage.NETWORK ? "正在连接无线网络" : "等待手机画面";
    String detail =
        !issue.isEmpty()
            ? issue
            : stage == Stage.WAITING
                ? "请在华为手机上连接新出现的 HiCar；首次按提示输入连接码。"
                : stage == Stage.VERIFYING
                    ? "首次连接请核对当前连接码；已有记录会尝试直接连接。"
                    : stage == Stage.NETWORK ? "正在等待手机加入接收网络。" : "请稍候；若长时间没有变化，可点重新连接。";
    return new Snapshot(title, detail, !issue.isEmpty());
  }
}
