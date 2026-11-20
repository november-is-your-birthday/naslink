package com.naslink.data.remote.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SshKeyGeneratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writesPrivateAndPublicKeyFiles() {
        val generated = SshKeyGenerator.generateToDirectory(
            directory = temp.root,
            baseName = "id_naslink_test",
            comment = "naslink-test",
        )

        val privateKey = String(Files.readAllBytes(generated.privateKeyFile.toPath()))
        val publicKey = String(Files.readAllBytes(generated.publicKeyFile.toPath()))

        assertEquals(temp.root.resolve("id_naslink_test"), generated.privateKeyFile)
        assertEquals(temp.root.resolve("id_naslink_test.pub"), generated.publicKeyFile)
        assertTrue(privateKey.contains("BEGIN RSA PRIVATE KEY"))
        assertTrue(publicKey.startsWith("ssh-rsa "))
        assertTrue(publicKey.trim().endsWith("naslink-test"))
        assertEquals(publicKey.trimEnd('\r', '\n'), generated.publicKey)
    }

    @Test
    fun sanitizesGeneratedFileNames() {
        assertEquals("id_naslink_my_server_22", SshKeyGenerator.safeKeyBaseName("my server:22"))
        assertEquals("id_naslink_key", SshKeyGenerator.safeKeyBaseName(""))
    }
}
