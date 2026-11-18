#define _DARWIN_C_SOURCE 1
#define _GNU_SOURCE 1
/* HiCarLab JNI adapter; based on CarLink hichain.py (MIT).
 * Native dependencies retain their own licenses. No PIN or key is logged. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <errno.h>
#include <openssl/sha.h>
#include "hichain.h"
#include "hks_file_api.h"
#include "hks_hardware_api.h"

/** HUKS 持久化文件所在目录的打开描述符，-1 表示尚未初始化。 */
static int root_fd = -1;

/** 由硬件种子派生的 32 字节设备身份（UDID）。 */
static uint8_t hardware_id[32];

/**
 * 校验 HUKS 允许访问的文件名白名单，防止路径穿越。
 *
 * @param s 待校验的文件名
 * @return 1 表示合法，0 表示非法
 */
static int valid_name(const char *s) {
  return s && (!strcmp(s, "info1.data") || !strcmp(s, "info2.data") ||
               !strcmp(s, "hks_keystore"));
}

/**
 * HUKS 文件回调：从持久化文件读取数据。
 *
 * @param n   文件名（白名单校验）
 * @param off 读取偏移，上限 1 MiB
 * @param b   输出缓冲区
 * @param len 读取长度，与偏移合计不得超过 1 MiB
 * @return 实际读取字节数；文件不存在返回 0；错误返回 -1
 */
static int32_t read_file(const char *n, uint32_t off, uint8_t *b, uint32_t len) {
  if (!valid_name(n) || off > 1048576 || len > 1048576 - off) return -1;
  int fd = openat(root_fd, n, O_RDONLY | O_NOFOLLOW);
  if (fd < 0) return errno == ENOENT ? 0 : -1;
  ssize_t r = pread(fd, b, len, off);
  close(fd);
  return (int32_t)r;
}

/**
 * HUKS 文件回调：向持久化文件写入数据并同步落盘。
 *
 * @param n   文件名（白名单校验）
 * @param off 写入偏移，上限 1 MiB
 * @param b   待写入数据
 * @param len 写入长度，与偏移合计不得超过 1 MiB
 * @return 成功返回 0，失败返回 -1
 */
static int32_t write_file(const char *n, uint32_t off, const uint8_t *b, uint32_t len) {
  if (!valid_name(n) || off > 1048576 || len > 1048576 - off) return -1;
  int fd = openat(root_fd, n, O_WRONLY | O_CREAT | O_NOFOLLOW, 0600);
  if (fd < 0) return -1;
  ssize_t r = pwrite(fd, b, len, off);
  int ok = r == (ssize_t)len && fsync(fd) == 0;
  close(fd);
  return ok ? 0 : -1;
}

/**
 * HUKS 文件回调：查询持久化文件大小。
 *
 * @param n 文件名（白名单校验）
 * @return 文件字节数；文件不存在返回 0；非普通文件、过大或错误返回 -1
 */
static int32_t file_size(const char *n) {
  if (!valid_name(n)) return -1;
  struct stat s;
  int fd = openat(root_fd, n, O_RDONLY | O_NOFOLLOW);
  if (fd < 0) return errno == ENOENT ? 0 : -1;
  int ok = fstat(fd, &s) == 0 && S_ISREG(s.st_mode) && s.st_size <= 1048576;
  close(fd);
  return ok ? (int32_t)s.st_size : -1;
}

/**
 * HUKS 硬件 UDID 回调：输出由种子派生的 32 字节设备身份。
 *
 * @param out 输出缓冲区
 * @param len 缓冲区长度，不足 32 字节返回 -1
 * @return 成功返回 0，失败返回 -1
 */
static int32_t udid(uint8_t *out, uint32_t len) {
  if (len < 32) return -1;
  memcpy(out, hardware_id, 32);
  return 0;
}

/** 注册给 HUKS 的文件读写回调表。 */
static struct hks_file_callbacks files = {read_file, write_file, file_size};

/** 一次 HiChain 认证会话在 JNI 层的上下文。 */
typedef struct {
  hc_handle handle;                   /**< native 认证框架会话句柄 */
  struct operation_parameter params;  /**< 本轮认证参数（身份、密钥长度等） */
  struct hc_pin pin;                  /**< 六位数字连接码 */
  struct session_identity identity;   /**< 回调注册使用的会话身份 */
  struct hc_call_back callbacks;      /**< 提供给 native 框架的回调函数集合 */
  uint8_t *packets[8];                /**< 待回传给 Java 的最多 8 个报文字节缓冲 */
  uint32_t lengths[8];                /**< 各报文的长度 */
  int count, error, result;           /**< 报文数量、错误标志与认证结果码 */
  struct hc_session_key key;          /**< 认证成功后产出的会话密钥 */
} Session;

/**
 * 从框架会话身份取回 JNI 层 Session 上下文。
 *
 * @param i 框架传入的会话身份
 * @return 对应的 Session 指针；无上下文时返回 NULL
 */
static Session *ctx(const struct session_identity *i) {
  return i ? (Session *)i->context : NULL;
}

/**
 * 框架回调：暂存一帧待发送给对端的认证报文。
 * 拒绝空报文、超长报文、含 0 字节的报文（JSON 协议）与超过 8 帧的批量。
 *
 * @param i    会话身份
 * @param data 报文字节
 * @param len  报文长度
 */
static void transmit(const struct session_identity *i, const void *data, uint32_t len) {
  Session *s = ctx(i);
  if (!s) return;
  if (!len || len > 65536 || s->count >= 8 || memchr(data, 0, len)) {
    s->error = 1;
    return;
  }
  uint8_t *b = malloc(len + 1);
  if (!b) {
    s->error = 1;
    return;
  }
  memcpy(b, data, len);
  b[len] = 0;
  s->packets[s->count] = b;
  s->lengths[s->count++] = len;
}

/**
 * 框架回调：提供本轮操作参数与 PIN。
 * 仅允许操作码 0、1、2、99。
 *
 * @param i  会话身份
 * @param op 操作码
 * @param p  输出的 PIN 结构
 * @param v  输出的操作参数
 */
static void params(const struct session_identity *i, int32_t op, struct hc_pin *p,
                   struct operation_parameter *v) {
  Session *s = ctx(i);
  if (!s) return;
  if (op != 0 && op != 1 && op != 2 && op != 99) {
    s->error = 1;
    return;
  }
  *p = s->pin;
  *v = s->params;
}

/**
 * 框架回调：保存认证成功产出的会话密钥（长度 1..128）。
 *
 * @param i 会话身份
 * @param k 会话密钥结构
 */
static void set_key(const struct session_identity *i, const struct hc_session_key *k) {
  Session *s = ctx(i);
  if (s && k && k->length > 0 && k->length <= 128)
    s->key = *k;
  else if (s)
    s->error = 1;
}

/**
 * 框架回调：记录认证结果码。
 *
 * @param i 会话身份
 * @param r 结果码
 */
static void result(const struct session_identity *i, int32_t r) {
  Session *s = ctx(i);
  if (s) s->result = r;
}

/**
 * 框架回调：用户确认操作，仅操作码 1、2 返回成功。
 *
 * @param i  会话身份（未使用）
 * @param op 操作码
 * @return 允许返回 0，否则返回 -1
 */
static int32_t confirm(const struct session_identity *i, int32_t op) {
  (void)i;
  return op == 1 || op == 2 ? 0 : -1;
}

/**
 * 把 Java String 拷贝到定长字节缓冲（超长则视为失败）。
 *
 * @param e   JNI 环境
 * @param str Java 字符串
 * @param out 目标缓冲
 * @param max 缓冲最大长度
 * @return 实际拷贝字节数；字符串为空或超长返回 0
 */
static int copy_string(JNIEnv *e, jstring str, uint8_t *out, int max) {
  if (!str) return 0;
  const char *p = (*e)->GetStringUTFChars(e, str, NULL);
  if (!p) return 0;
  int n = (int)strlen(p);
  if (n > 0 && n <= max) memcpy(out, p, n); else n = 0;
  (*e)->ReleaseStringUTFChars(e, str, p);
  return n;
}

/**
 * JNI: 初始化 HUKS 运行时。
 * 用传入的种子派生硬件 UDID，打开权限为 0700 的持久化目录并注册文件/UDID 回调。
 *
 * @param e    JNI 环境
 * @param c    jclass（未使用）
 * @param path HUKS 持久化目录路径
 * @param seed 硬件种子字符串
 * @return 0 表示成功，负数表示失败（已初始化时直接返回 0）
 */
JNIEXPORT jint JNICALL Java_local_hicarlab_NativeAuth_init(JNIEnv *e, jclass c, jstring path, jstring seed) {
  (void)c;
  if (root_fd >= 0) return 0;
  uint8_t buf[256];
  int n = copy_string(e, seed, buf, sizeof(buf));
  if (!n) return -1;
  SHA256(buf, n, hardware_id);
  memset(buf, 0, sizeof(buf));
  const char *p = (*e)->GetStringUTFChars(e, path, NULL);
  if (!p) return -1;
  root_fd = open(p, O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
  (*e)->ReleaseStringUTFChars(e, path, p);
  if (root_fd < 0) return -2;
  struct stat s;
  if (fstat(root_fd, &s) || s.st_mode & 0077) {
    close(root_fd);
    root_fd = -1;
    return -3;
  }
  int r = hks_register_file_callbacks(&files);
  if (!r) r = hks_register_get_hardware_udid_callback(udid);
  if (r) {
    close(root_fd);
    root_fd = -1;
  }
  return r;
}

/**
 * JNI: 创建一次认证会话。
 * 校验角色、本机/对端身份与六位数字 PIN 后，向 native 框架注册会话身份与回调。
 *
 * @param e    JNI 环境
 * @param c    jclass（未使用）
 * @param self 本机身份字符串
 * @param peer 对端身份字符串
 * @param pin  六位数字连接码
 * @param role 角色（1 或 2）
 * @return Session 指针（作为 jlong 句柄）；失败返回 0
 */
JNIEXPORT jlong JNICALL Java_local_hicarlab_NativeAuth_create(JNIEnv *e, jclass c, jstring self, jstring peer, jstring pin, jint role) {
  (void)c;
  if (root_fd < 0 || (role != 1 && role != 2)) return 0;
  Session *s = calloc(1, sizeof(*s));
  if (!s) return 0;
  s->result = -1;
  s->params.self_auth_id.length = copy_string(e, self, s->params.self_auth_id.auth_id, 64);
  s->params.peer_auth_id.length = copy_string(e, peer, s->params.peer_auth_id.auth_id, 64);
  s->pin.length = copy_string(e, pin, s->pin.pin, 16);
  s->params.key_length = 32;
  if (!s->params.self_auth_id.length || !s->params.peer_auth_id.length || s->pin.length != 6) {
    free(s);
    return 0;
  }
  for (int j = 0; j < 6; j++)
    if (s->pin.pin[j] < '0' || s->pin.pin[j] > '9') {
      free(s);
      return 0;
    }
  s->identity.package_name.length = 16;
  memcpy(s->identity.package_name.name, "com.huawei.hicar", 16);
  s->identity.service_type.length = 9;
  memcpy(s->identity.service_type.type, "CarDevice", 9);
  s->identity.context = s;
  s->callbacks = (struct hc_call_back){transmit, params, set_key, result, confirm};
  s->handle = get_instance(&s->identity, role, &s->callbacks);
  if (!s->handle) {
    memset(s, 0, sizeof(*s));
    free(s);
    return 0;
  }
  return (jlong)(intptr_t)s;
}

/**
 * JNI: 推进一步认证。
 * data 为空时发起 PAKE，否则把输入报文交给 native 框架处理；
 * 返回数组第 0 项为元数据（结果码、错误码、密钥长度、错误标志），其余为待发送报文。
 *
 * @param e          JNI 环境
 * @param c          jclass（未使用）
 * @param handle     会话句柄
 * @param data       对端输入报文，为空表示发起认证
 * @param key_length 期望密钥长度（16 或 32）
 * @return 字节数组列表；参数非法返回 NULL
 */
JNIEXPORT jobjectArray JNICALL Java_local_hicarlab_NativeAuth_step(JNIEnv *e, jclass c, jlong handle, jbyteArray data, jint key_length) {
  (void)c;
  Session *s = (Session *)(intptr_t)handle;
  if (!s) return NULL;
  if (key_length != 16 && key_length != 32) return NULL;
  s->params.key_length = key_length;
  int code;
  if (!data) {
    code = start_pake(s->handle, &s->params);
  } else {
    jsize n = (*e)->GetArrayLength(e, data);
    if (n <= 0 || n > 65536) return NULL;
    uint8_t *b = malloc(n + 1);
    if (!b) return NULL;
    (*e)->GetByteArrayRegion(e, data, 0, n, (jbyte *)b);
    b[n] = 0;
    struct uint8_buff v = {b, (uint32_t)n, (uint32_t)n};
    code = receive_data(s->handle, &v);
    memset(b, 0, n);
    free(b);
  }
  jclass bytes = (*e)->FindClass(e, "[B");
  jobjectArray out = (*e)->NewObjectArray(e, s->count + 1, bytes, NULL);
  int32_t meta[4] = {code, s->result, (int32_t)s->key.length, s->error};
  jbyteArray m = (*e)->NewByteArray(e, sizeof(meta));
  (*e)->SetByteArrayRegion(e, m, 0, sizeof(meta), (jbyte *)meta);
  (*e)->SetObjectArrayElement(e, out, 0, m);
  (*e)->DeleteLocalRef(e, m);
  for (int j = 0; j < s->count; j++) {
    jbyteArray b = (*e)->NewByteArray(e, s->lengths[j]);
    (*e)->SetByteArrayRegion(e, b, 0, s->lengths[j], (jbyte *)s->packets[j]);
    (*e)->SetObjectArrayElement(e, out, j + 1, b);
    (*e)->DeleteLocalRef(e, b);
    memset(s->packets[j], 0, s->lengths[j]);
    free(s->packets[j]);
  }
  s->count = 0;
  return out;
}

/* Copy only a completed session's own key into private process memory. */
/**
 * JNI: 导出已完成认证的会话密钥。
 * 仅在无错误、结果为成功且密钥长度为 16 或 32 时返回。
 *
 * @param e      JNI 环境
 * @param c      jclass（未使用）
 * @param handle 会话句柄
 * @return 会话密钥字节数组；条件不满足返回 NULL
 */
JNIEXPORT jbyteArray JNICALL Java_local_hicarlab_NativeAuth_sessionKey(JNIEnv *e, jclass c, jlong handle) {
  (void)c;
  Session *s = (Session *)(intptr_t)handle;
  if (!s || s->error || s->result != 0 || (s->key.length != 16 && s->key.length != 32)) return NULL;
  jbyteArray out = (*e)->NewByteArray(e, (jsize)s->key.length);
  if (out) (*e)->SetByteArrayRegion(e, out, 0, (jsize)s->key.length, (const jbyte *)s->key.session_key);
  return out;
}

/**
 * JNI: 关闭并释放认证会话。
 * 销毁 native 句柄，清零并释放所有暂存报文及 Session 自身。
 *
 * @param e      JNI 环境（未使用）
 * @param c      jclass（未使用）
 * @param handle 会话句柄
 */
JNIEXPORT void JNICALL Java_local_hicarlab_NativeAuth_close(JNIEnv *e, jclass c, jlong handle) {
  (void)e;
  (void)c;
  Session *s = (Session *)(intptr_t)handle;
  if (!s) return;
  if (s->handle) destroy(&s->handle);
  for (int j = 0; j < s->count; j++) {
    memset(s->packets[j], 0, s->lengths[j]);
    free(s->packets[j]);
  }
  memset(s, 0, sizeof(*s));
  free(s);
}
