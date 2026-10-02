package tech.xdomhatter.core.store;

import com.google.gson.JsonSyntaxException;
import tech.xdomhatter.core.util.Json;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSH 凭据保险库：主密码经 PBKDF2 派生 AES-256 密钥，凭据以 AES-GCM 逐条加密。
 * 用“加密已知魔数”作为主密码校验挑战。
 */
public class CredentialVault {
    public static final class WrongPasswordException extends RuntimeException {
        public WrongPasswordException(String message) {
            super(message);
        }
    }

    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int IV_LEN = 12;
    private static final byte[] MAGIC = "justserving-vault-ok".getBytes(StandardCharsets.UTF_8);

    private final Path file;
    private final SecureRandom rnd = new SecureRandom();
    private Data data;
    private byte[] key;

    private static class Data {
        String salt;
        String challenge;
        Map<String, String> entries = new LinkedHashMap<>();
    }

    public CredentialVault(Path file) {
        this.file = file;
        this.data = load();
    }

    public boolean isInitialized() {
        return data != null && data.challenge != null;
    }

    public boolean isUnlocked() {
        return key != null;
    }

    public void initialize(char[] master) throws GeneralSecurityException {
        if (master.length == 0) throw new IllegalArgumentException("主密码不能为空");
        Data d = new Data();
        byte[] salt = new byte[16];
        rnd.nextBytes(salt);
        d.salt = b64(salt);
        d.challenge = b64(encryptWith(derive(master, salt), MAGIC));
        this.data = d;
        this.key = derive(master, salt);
        save();
    }

    public void unlock(char[] master) throws GeneralSecurityException {
        if (!isInitialized()) throw new IllegalStateException("保险库尚未初始化");
        byte[] pt = decryptChallenge(master);
        if (!MessageDigest.isEqual(pt, MAGIC)) throw new WrongPasswordException("主密码错误");
        key = derive(master, ub64(data.salt));
    }

    public void lock() {
        key = null;
    }

    public void changeMasterPassword(char[] oldPw, char[] newPw) throws GeneralSecurityException {
        decryptChallenge(oldPw);
        byte[] ns = new byte[16];
        rnd.nextBytes(ns);
        byte[] nk = derive(newPw, ns);
        Map<String, String> re = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : data.entries.entrySet()) {
            byte[] v = decryptWith(key, ub64(e.getValue()));
            re.put(e.getKey(), b64(encryptWith(nk, v)));
        }
        data.salt = b64(ns);
        data.challenge = b64(encryptWith(nk, MAGIC));
        data.entries = re;
        key = nk;
        save();
    }

    public synchronized void putSecret(String name, String value) throws GeneralSecurityException {
        requireUnlocked();
        if (value == null) {
            data.entries.remove(name);
        } else {
            data.entries.put(name, b64(encryptWith(key, value.getBytes(StandardCharsets.UTF_8))));
        }
        save();
    }

    public synchronized String getSecret(String name) throws GeneralSecurityException {
        requireUnlocked();
        String s = data.entries.get(name);
        return s == null ? null : new String(decryptWith(key, ub64(s)), StandardCharsets.UTF_8);
    }

    public synchronized void removeSecret(String name) {
        if (data.entries.remove(name) != null) save();
    }

    private void requireUnlocked() {
        if (key == null) throw new IllegalStateException("保险库未解锁");
    }

    /** 用主密码解密挑战魔数；GCM 认证失败（口令错误）时统一抛出 WrongPasswordException。 */
    private byte[] decryptChallenge(char[] master) throws GeneralSecurityException {
        try {
            return decryptWith(derive(master, ub64(data.salt)), ub64(data.challenge));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPasswordException("主密码错误");
        }
    }

    private static byte[] derive(char[] pw, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] encryptWith(byte[] k, byte[] pt) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(pt);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] decryptWith(byte[] k, byte[] ivct) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, ivct, 0, IV_LEN));
        return c.doFinal(ivct, IV_LEN, ivct.length - IV_LEN);
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存保险库失败: " + file, e);
        }
    }

    private Data load() {
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            Data d = Json.GSON.fromJson(s, Data.class);
            if (d != null && d.salt != null) {
                if (d.entries == null) d.entries = new LinkedHashMap<>();
                return d;
            }
        } catch (NoSuchFileException | JsonSyntaxException ignored) {
        } catch (IOException ignored) {
        }
        return new Data();
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] ub64(String s) {
        return Base64.getDecoder().decode(s);
    }
}
