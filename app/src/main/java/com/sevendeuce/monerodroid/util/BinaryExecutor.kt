package com.sevendeuce.monerodroid.util

/**
 * Builds the command to run a downloaded monerod via the system dynamic linker.
 *
 * On Android 10+ (API 29+) SELinux denies execute_no_trans on app_data_file, so a binary in
 * the app's data dir cannot be execve()'d directly. Invoking it as an argument to the system
 * linker loads it via mmap(PROT_EXEC), which is permitted. minSdk is 29, so this is the single
 * supported execution path. The linker MUST match the binary's architecture.
 */
object BinaryExecutor {

    /** `/system/bin/linker64` for a 64-bit binary, `/system/bin/linker` for 32-bit. */
    fun linkerForArch(is64Bit: Boolean): String =
        if (is64Bit) "/system/bin/linker64" else "/system/bin/linker"

    /** argv to run [binaryPath] (with [args]) via [linkerPath]. */
    fun linkerCommand(linkerPath: String, binaryPath: String, args: List<String>): List<String> =
        listOf(linkerPath, binaryPath) + args

    /** True if the device's selected monerod build is 64-bit (arm64-v8a). */
    fun is64BitArch(): Boolean =
        ArchitectureDetector.detectArchitecture() == CpuArchitecture.ARM_V8
}
