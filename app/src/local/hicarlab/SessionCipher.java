package local.hicarlab;

// CarLink cipher.py (MIT): authenticated HiChain key prefix, per-record AES CTR.
import java.io.IOException;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

final class SessionCipher implements AutoCloseable {
  private byte[] key;
  private final SecureRandom random = new SecureRandom();
  private final int limit;

  /** 校验会话密钥长度为 16 或 32 字节且记录上限在 17 到 10485760 之间，取密钥前 16 字节并保存上限。 */
  SessionCipher(byte[] sessionKey, int maxRecord) {
    if (sessionKey == null
        || (sessionKey.length != 16 && sessionKey.length != 32)
        || maxRecord < 17
        || maxRecord > 10485760)
      throw new IllegalArgumentException("authenticated key/record size");
    key = Arrays.copyOf(sessionKey, 16);
    limit = maxRecord;
  }

  /** 校验密钥、16 字节 IV 与记录长度后使用 AES/CTR/NoPadding 对数据进行加解密。 */
  synchronized byte[] transform(byte[] data, byte[] iv)
      throws GeneralSecurityException, IOException {
    if (key == null
        || iv == null
        || iv.length != 16
        || data == null
        || data.length < 1
        || data.length >= limit) throw new IOException("cipher record");
    Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return c.doFinal(data);
  }

  /** 生成 16 字节随机 IV，加密明文并将 IV 与密文拼接为一条记录返回。 */
  synchronized byte[] encrypt(byte[] plain) throws GeneralSecurityException, IOException {
    if (plain == null || plain.length < 1 || plain.length + 16 >= limit)
      throw new IOException("cipher record size");
    byte[] iv = new byte[16];
    random.nextBytes(iv);
    return NearbyWire.join(iv, transform(plain, iv));
  }

  /** 校验记录长度后取前 16 字节作为 IV，其余部分作为密文解密。 */
  synchronized byte[] decrypt(byte[] record) throws GeneralSecurityException, IOException {
    if (record == null || record.length <= 16 || record.length >= limit)
      throw new IOException("encrypted record size");
    return transform(Arrays.copyOfRange(record, 16, record.length), Arrays.copyOf(record, 16));
  }

  /** 将密钥字节清零并置空以清除敏感材料。 */
  @Override
  public synchronized void close() {
    if (key != null) Arrays.fill(key, (byte) 0);
    key = null;
  }
}
