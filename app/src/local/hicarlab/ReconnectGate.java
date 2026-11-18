package local.hicarlab;

/** Coalesce media/control disconnects from one generation into one restart. */
final class ReconnectGate {
  private int pending;
  private boolean requested;

  /** 对同一 generation 的重复重连请求返回 false，首次请求记录该 generation 并返回 true。 */
  synchronized boolean request(int generation) {
    if (requested && pending == generation) return false;
    pending = generation;
    requested = true;
    return true;
  }

  /** 清除已请求标记，允许下一 generation 重新提出重连请求。 */
  synchronized void reset() {
    requested = false;
  }
}
