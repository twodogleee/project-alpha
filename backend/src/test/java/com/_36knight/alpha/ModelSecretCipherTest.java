package com._36knight.alpha;

import com._36knight.alpha.model.ModelConfigurationException;
import com._36knight.alpha.model.ModelSecretCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 主密钥重开、随机 nonce 与损坏边界，不使用真实凭证。@author Andersen */
class ModelSecretCipherTest {
    /** 隔离私有测试文件，测试结束由 JUnit 清理。 */
    @TempDir Path directory;

    /** 只接受当前密文版本，即使其他版本带有完整的密文主体也要拒绝。 */
    @Test
    void rejectsUnsupportedCiphertextVersion() {
        var cipher = new ModelSecretCipher("", directory.resolve("master.key").toString());
        String current = cipher.encrypt("synthetic-key");
        assertThat(current).startsWith("v2:");
        assertThatThrownBy(() -> cipher.decrypt("v1:" + current.substring(3))).isInstanceOf(ModelConfigurationException.class);
    }

    /** 新加密随机化，重新创建服务后仍可解密，文件权限为 0600。 */
    @Test
    void encryptsWithPrivateKeyAndSurvivesRecreation() throws Exception {
        String file = directory.resolve("master.key").toString();
        var cipher = new ModelSecretCipher("", file);
        String first = cipher.encrypt("synthetic-key");
        assertThat(first).isNotEqualTo(cipher.encrypt("synthetic-key")).doesNotContain("synthetic-key");
        assertThat(new ModelSecretCipher("", file).decrypt(first)).isEqualTo("synthetic-key");
        assertThat(Files.getPosixFilePermissions(Path.of(file))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /** 密文认证失败或文件缺失时必须失败，不能新生成替代主密钥。 */
    @Test
    void rejectsTamperedCiphertextAndMissingMasterKey() throws Exception {
        Path file = directory.resolve("master.key");
        var cipher = new ModelSecretCipher("", file.toString());
        String encrypted = cipher.encrypt("synthetic-key");
        String changed = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        assertThatThrownBy(() -> cipher.decrypt(changed)).isInstanceOf(ModelConfigurationException.class);
        Files.delete(file);
        assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(ModelConfigurationException.class);
        assertThat(Files.exists(file)).isFalse();
    }
}
