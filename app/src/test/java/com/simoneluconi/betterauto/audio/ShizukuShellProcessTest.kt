package com.simoneluconi.betterauto.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellProcessTest {
    @Test
    fun commandRunsAppProcessInTheBackgroundWithEveryPartQuoted() {
        val command = ShizukuShellProcess.command(
            apk = "/data/app/~~Ab==/com.example-Cd==/base.apk",
            niceName = "com.example:shell",
            mainClass = "com.example.Main",
            arguments = listOf("com.example.shellservice", "1234-abcd", "0"),
        )
        assertEquals(
            "CLASSPATH='/data/app/~~Ab==/com.example-Cd==/base.apk' /system/bin/app_process /system/bin " +
                "--nice-name='com.example:shell' 'com.example.Main' 'com.example.shellservice' '1234-abcd' '0' &",
            command,
        )
    }

    @Test
    fun quotesInsideArgumentsCannotEndTheQuoting() {
        val command = ShizukuShellProcess.command("/a b/it's.apk", "n", "M", listOf("x'; reboot; '"))
        assertTrue(command.startsWith("CLASSPATH='/a b/it'\\''s.apk' /system/bin/app_process "))
        assertTrue(command.endsWith(" 'M' 'x'\\''; reboot; '\\''' &"))
    }

    @Test
    fun onlyTheShellUserOrRootMayHandAServiceOver() {
        assertTrue(ShizukuShellProcess.trustedCaller(2000))
        assertTrue(ShizukuShellProcess.trustedCaller(0))
        assertFalse(ShizukuShellProcess.trustedCaller(1000))
        assertFalse(ShizukuShellProcess.trustedCaller(10234))
        // The shell user of a second profile is not the shell that Shizuku runs as
        assertFalse(ShizukuShellProcess.trustedCaller(1002000))
    }
}
