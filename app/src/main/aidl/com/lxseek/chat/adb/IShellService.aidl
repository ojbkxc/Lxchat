// Shizuku user-service interface used to execute shell commands with
// root/shell privileges in the isolated Shizuku process.
//
// Shizuku's UserService mechanism bounces work over a Binder to a separate
// process (spawned by the Shizuku server with the privileged UID), which is
// the recommended replacement for the private Shizuku.newProcess() call.
package com.lxseek.chat.adb;

interface IShellService {
    // Reserved destroy method mandated by the Shizuku server (transaction
    // code 16777114 in AIDL). Invoked when the user service is removed.
    void destroy() = 16777114;

    // User-defined: exit the service process.
    void exit() = 1;

    // Execute a shell command and return combined stdout+stderr (capped).
    String exec(String cmd) = 2;
}