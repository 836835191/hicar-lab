package local.hicarlab;

import android.content.*;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

/** Owns only the temporary group created by this experiment. */
final class P2pHotspot implements AutoCloseable {
  interface Events {
    void ready(NetworkResource.Offer offer);

    void failed(String reason);

    void log(String message);
  }

  private final Context context;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final WifiP2pManager manager;
  private WifiP2pManager.Channel channel;
  private final Events events;
  private volatile boolean closed, created;
  private int attempts;

  /** 保存应用上下文与事件回调并获取系统 Wi-Fi Direct 服务管理器。 */
  P2pHotspot(Context c, Events events) {
    context = c.getApplicationContext();
    this.events = events;
    manager = (WifiP2pManager) c.getSystemService(Context.WIFI_P2P_SERVICE);
  }

  /** 检查系统版本与管理器可用性，初始化 P2P 通道并确认无已有组后调用 create 创建热点。 */
  void start() {
    if (Build.VERSION.SDK_INT < 29 || manager == null) {
      events.failed("系统未提供所需 Wi-Fi Direct 接口");
      return;
    }
    try {
      channel =
          manager.initialize(
              context,
              Looper.getMainLooper(),
              () -> {
                if (!closed) events.failed("Wi-Fi Direct 服务断开");
              });
      if (channel == null) {
        events.failed("Wi-Fi Direct 初始化失败");
        return;
      }
      manager.requestGroupInfo(
          channel,
          existing -> {
            if (closed) return;
            if (existing != null) {
              String name = existing.getNetworkName();
              java.util.Collection<android.net.wifi.p2p.WifiP2pDevice> clients =
                  existing.getClientList();
              if ((name != null && name.contains("HLHiCarLab"))
                  || clients == null
                  || clients.isEmpty()) {
                events.log("发现空闲的 Wi-Fi Direct 组，先移除再重建本轮热点。");
                manager.removeGroup(
                    channel,
                    new WifiP2pManager.ActionListener() {
                      public void onSuccess() {
                        handler.postDelayed(P2pHotspot.this::create, 300);
                      }

                      public void onFailure(int reason) {
                        if (!closed) events.failed("无法移除遗留热点，系统代码 " + reason);
                      }
                    });
              } else events.failed("已有 Wi-Fi Direct 连接，实验未修改它");
              return;
            }
            create();
          });
    } catch (RuntimeException e) {
      events.failed("Wi-Fi Direct 接口拒绝：" + e.getClass().getSimpleName());
    }
  }

  /** 生成随机密码与 SSID，配置 5GHz 信道 149 的临时组调用 createGroup，成功后轮询组信息。 */
  private void create() {
    try {
      byte[] random = new byte[12];
      new SecureRandom().nextBytes(random);
      StringBuilder password = new StringBuilder();
      for (byte b : random) password.append(String.format(Locale.ROOT, "%02x", b & 255));
      WifiP2pConfig config =
          new WifiP2pConfig.Builder()
              .setNetworkName("DIRECT-HLHiCarLab-" + password.substring(0, 4))
              .setPassphrase(password.toString())
              .setGroupOperatingFrequency(5180)
              .enablePersistentMode(false)
              .build();
      events.log("正在创建本轮临时 Wi-Fi Direct 热点（5 GHz 信道 36）；不记录密码。");
      manager.createGroup(
          channel,
          config,
          new WifiP2pManager.ActionListener() {
            public void onSuccess() {
              created = true;
              if (closed) {
                remove();
                return;
              }
              poll();
            }

            public void onFailure(int reason) {
              if (!closed) events.failed("临时热点创建失败，系统代码 " + reason);
            }
          });
    } catch (RuntimeException e) {
      events.failed("热点配置失败：" + e.getClass().getSimpleName());
    }
  }

  /** 轮询组信息，确认成为 GO 并取得真实 IPv4 与可用 BSSID 后推断客户端 IP，构造 Offer 回调 ready，最多重试 30 次。 */
  private void poll() {
    if (closed) return;
    manager.requestGroupInfo(
        channel,
        group -> {
          if (closed) return;
          if (group == null) {
            if (++attempts < 30) handler.postDelayed(this::poll, 500);
            else events.failed("临时热点就绪超时");
            return;
          }
          if (!group.isGroupOwner()) {
            events.failed("未成为本轮热点所有者");
            return;
          }
          try {
            String bssid = null;
            for (String method : new String[] {"getGroupOwnerBssid", "getBssid", "getGoBssid"}) {
              try {
                Object address = group.getClass().getMethod(method).invoke(group);
                if (usableMac(address == null ? null : address.toString())) {
                  bssid = address.toString();
                  events.log("本轮热点 BSSID 由系统组信息接口提供（不写日志）。");
                  break;
                }
              } catch (ReflectiveOperationException | RuntimeException ignored) {
              }
            }
            String owner = null;
            NetworkInterface network = NetworkInterface.getByName(group.getInterface());
            if (network != null)
              for (InterfaceAddress a : network.getInterfaceAddresses())
                if (a.getAddress() instanceof Inet4Address
                    && !a.getAddress().isLoopbackAddress()
                    && a.getNetworkPrefixLength() == 24) owner = a.getAddress().getHostAddress();
            if (owner == null) {
              if (++attempts < 30) {
                handler.postDelayed(this::poll, 500);
                return;
              }
              events.failed("未取得本轮热点真实 IPv4 地址");
              return;
            }
            if (!usableMac(bssid) && group.getOwner() != null) {
              String address = group.getOwner().deviceAddress;
              if (usableMac(address)) {
                bssid = address;
                events.log("本轮热点 BSSID 由所有者 P2P 设备地址提供（不写日志）。");
              }
            }
            if (!usableMac(bssid))
              bssid = OwnGroupAddress.read(network, group.getInterface(), events);
            if (!usableMac(bssid) && network != null) {
              String derived = bssidFromLinkLocal(network);
              if (usableMac(derived)) {
                bssid = derived;
                events.log("本轮热点 BSSID 由接口 IPv6 链路本地地址推导（不写日志）。");
              }
            }
            if (!usableMac(bssid)) {
              events.failed("系统未向普通应用提供本轮热点 BSSID；未发送占位地址");
              return;
            }
            int dot = owner.lastIndexOf('.');
            String client = owner.substring(0, dot + 1) + (owner.endsWith(".2") ? "3" : "2");
            NetworkResource.Offer offer =
                new NetworkResource.Offer(
                    group.getNetworkName(),
                    bssid,
                    group.getPassphrase(),
                    owner,
                    client,
                    group.getFrequency(),
                    4005);
            events.log("临时热点已就绪：频率 " + group.getFrequency() + " MHz，真实 BSSID 与 IPv4 已取得（值不写日志）。");
            events.ready(offer);
          } catch (Exception e) {
            events.failed("热点参数核验失败：" + e.getClass().getSimpleName());
          }
        });
  }

  /** 从热点接口的 IPv6 链路本地地址按 EUI-64 规则反推接口 MAC，作为 BSSID 候选。 */
  private static String bssidFromLinkLocal(NetworkInterface network) {
    try {
      for (InterfaceAddress candidate : network.getInterfaceAddresses()) {
        java.net.InetAddress address = candidate.getAddress();
        if (!(address instanceof java.net.Inet6Address) || !address.isLinkLocalAddress()) continue;
        byte[] bytes = address.getAddress();
        if (bytes.length != 16 || (bytes[0] & 255) != 0xfe || (bytes[1] & 255) != 0x80) continue;
        byte[] mac = new byte[6];
        mac[0] = (byte) (bytes[8] ^ 0x02);
        mac[1] = bytes[9];
        mac[2] = bytes[10];
        mac[3] = bytes[13];
        mac[4] = bytes[14];
        mac[5] = bytes[15];
        String result = OwnGroupAddress.format(mac);
        if (result != null) return result;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  /** 判断字符串是否为格式合法且非全零与非 02:00:00:00:00:00 的 MAC 地址。 */
  private static boolean usableMac(String value) {
    return value != null
        && value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
        && !value.equals("02:00:00:00:00:00")
        && !value.equals("00:00:00:00:00:00");
  }

  /** 若已创建组且通道有效则调用 removeGroup 删除本轮临时热点并记录结果。 */
  private void remove() {
    if (created && channel != null) {
      created = false;
      manager.removeGroup(
          channel,
          new WifiP2pManager.ActionListener() {
            public void onSuccess() {
              events.log("本轮临时热点已移除。");
            }

            public void onFailure(int reason) {
              events.log("临时热点移除未确认，系统代码 " + reason);
            }
          });
    }
  }

  /** 标记关闭、清除延迟任务并移除临时热点，实现 AutoCloseable。 */
  @Override
  public void close() {
    closed = true;
    handler.removeCallbacksAndMessages(null);
    remove();
  }
}
