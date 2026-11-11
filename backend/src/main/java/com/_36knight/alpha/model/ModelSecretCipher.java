package com._36knight.alpha.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 使用 AES-256-GCM 保存模型密钥；主密钥与数据库分开保管，不对外提供读取能力。
 * @author Andersen
 */
@Component
public class ModelSecretCipher {
    /** 模型密钥唯一支持的密文版本前缀。 */
    private static final String PREFIX = "v2:";
    /** 新写入的 v2 密文绑定当前项目及用途。 */
    private static final byte[] V2_CONTEXT = "project-alpha:model-config:2".getBytes(StandardCharsets.UTF_8);
    /** 安全随机数用于主密钥及每次加密独立的 12 字节 nonce。 */
    private final SecureRandom random = new SecureRandom();
    /** 可由部署后端环境提供的 32 字节 Base64 主密钥。 */
    private final String environmentKey;
    /** 本地自动生成的私有主密钥文件，不随数据库或 JAR 保存。 */
    private final Path keyFile;

    /** 不在启动时生成密钥，仅首次加密需要时创建。 */
    public ModelSecretCipher(@Value("${alpha.model-config.encryption-key:}") String environmentKey,
            @Value("${alpha.model-config.key-file:./data/model-config.key}") String keyFile) {
        this.environmentKey = environmentKey;
        this.keyFile = Path.of(keyFile).toAbsolutePath().normalize();
    }

    /** 加密为带版本前缀的 nonce + 密文 + 认证标签，不包含明文。 */
    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey(true), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(V2_CONTEXT);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException | IOException | IllegalArgumentException | UnsupportedOperationException exception) {
            throw unavailable();
        }
    }

    /** 解密必须使用原主密钥，文件缺失或认证失败不能自动换密钥。 */
    public String decrypt(String value) {
        try {
            if (!value.startsWith(PREFIX)) throw new IllegalArgumentException("Unsupported ciphertext");
            byte[] encrypted = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (encrypted.length < 29) throw new IllegalArgumentException("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey(false), new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
            cipher.updateAAD(V2_CONTEXT);
            return new String(cipher.doFinal(encrypted, 12, encrypted.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException | UnsupportedOperationException exception) {
            throw unavailable();
        }
    }

    /** 后端环境优先；本地新文件以 0600 创建，已有文件不会覆盖。 */
    private synchronized SecretKeySpec masterKey(boolean allowCreate) throws IOException {
        if (!environmentKey.isBlank()) return decodeKey(environmentKey.strip());
        if (Files.isSymbolicLink(keyFile)) throw new IOException("Unsafe key file");
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS) && allowCreate) {
            Files.createDirectories(keyFile.getParent());
            byte[] key = new byte[32];
            random.nextBytes(key);
            byte[] encoded = Base64.getEncoder().encode(key);
            // 本地为 POSIX 系统；其他文件系统需由部署环境提供主密钥，避免宽权限落盘。
            try (var channel = Files.newByteChannel(keyFile,
                    java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        }
        if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing key file");
        if (!Files.getPosixFilePermissions(keyFile).equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("Key file permissions must be private");
        }
        return decodeKey(Files.readString(keyFile).strip());
    }

    /** 主密钥固定为 32 字节，不将错误值带入异常。 */
    private SecretKeySpec decodeKey(String value) {
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length != 32) throw new IllegalArgumentException("Invalid key length");
        return new SecretKeySpec(decoded, "AES");
    }

    /** 密钥文件或密文损坏时提示恢复原主密钥，隐藏底层信息。 */
    private ModelConfigurationException unavailable() {
        return new ModelConfigurationException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_SECRET_UNAVAILABLE",
                "模型密钥无法安全读写，请检查或恢复后端加密主密钥配置");
    }
}
