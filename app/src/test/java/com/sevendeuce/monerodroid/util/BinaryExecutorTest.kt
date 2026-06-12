package com.sevendeuce.monerodroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryExecutorTest {

    @Test
    fun `64-bit uses linker64`() {
        assertEquals("/system/bin/linker64", BinaryExecutor.linkerForArch(is64Bit = true))
    }

    @Test
    fun `32-bit uses linker`() {
        assertEquals("/system/bin/linker", BinaryExecutor.linkerForArch(is64Bit = false))
    }

    @Test
    fun `command places linker first, then binary, then args`() {
        val cmd = BinaryExecutor.linkerCommand(
            "/system/bin/linker64",
            "/data/.../monerod",
            listOf("--config-file", "/x/monerod.conf", "--non-interactive")
        )
        assertEquals(
            listOf(
                "/system/bin/linker64",
                "/data/.../monerod",
                "--config-file", "/x/monerod.conf", "--non-interactive"
            ),
            cmd
        )
    }

    @Test
    fun `command with no args is just linker and binary`() {
        assertEquals(
            listOf("/system/bin/linker", "/p/monerod"),
            BinaryExecutor.linkerCommand("/system/bin/linker", "/p/monerod", emptyList())
        )
    }
}
