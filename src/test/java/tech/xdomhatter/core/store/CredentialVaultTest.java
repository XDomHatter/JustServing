package tech.xdomhatter.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CredentialVaultTest {
    @TempDir
    Path dir;

    private CredentialVault fresh() {
        return new CredentialVault(dir.resolve("vault.json"));
    }

    @Test
    void notInitializedByDefault() {
        assertFalse(fresh().isInitialized());
    }

    @Test
    void initUnlockRoundtrip() throws Exception {
        CredentialVault v = fresh();
        assertFalse(v.isInitialized());
        v.initialize("master-pw".toCharArray());
        assertTrue(v.isInitialized());
        assertTrue(v.isUnlocked());

        v.putSecret("k1", "hello-秘密");
        assertEquals("hello-秘密", v.getSecret("k1"));

        CredentialVault v2 = fresh();
        assertTrue(v2.isInitialized());
        assertThrows(IllegalStateException.class, () -> v2.getSecret("k1"));
        v2.unlock("master-pw".toCharArray());
        assertEquals("hello-秘密", v2.getSecret("k1"));
        assertNull(v2.getSecret("missing"));
    }

    @Test
    void wrongPasswordThrows() throws Exception {
        CredentialVault v = fresh();
        v.initialize("right".toCharArray());
        CredentialVault.WrongPasswordException e = assertThrows(
                CredentialVault.WrongPasswordException.class,
                () -> v.unlock("wrong".toCharArray()));
        assertTrue(e.getMessage().contains("主密码错误"));
    }

    @Test
    void changeMasterPassword() throws Exception {
        CredentialVault v = fresh();
        v.initialize("old".toCharArray());
        v.putSecret("a", "1");
        v.putSecret("b", "2");
        v.changeMasterPassword("old".toCharArray(), "new".toCharArray());

        CredentialVault v2 = fresh();
        assertThrows(CredentialVault.WrongPasswordException.class, () -> v2.unlock("old".toCharArray()));
        v2.unlock("new".toCharArray());
        assertEquals("1", v2.getSecret("a"));
        assertEquals("2", v2.getSecret("b"));
    }

    @Test
    void emptyMasterRejected() {
        CredentialVault v = fresh();
        assertThrows(IllegalArgumentException.class, () -> v.initialize("".toCharArray()));
    }
}
