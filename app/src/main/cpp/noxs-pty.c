/*
 * Noxs — original implementation.
 * Native PTY bridge: creates a real pseudo-terminal for the Debian userspace
 * session (proot child), performs window-size ioctls and signal delivery.
 *
 * Security notes:
 *  - The child is a direct execve() of the proot binary living in the app's
 *    private storage; no shell interpolation happens in native code.
 *  - File descriptors are never leaked to the child beyond stdio.
 *  - No device files are opened; strictly the PTY master allocated by the OS.
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#define TAG "noxs-pty"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static jclass g_illegal_state;

static int g_master_fd = -1;
static pid_t g_child_pid = -1;

static int write_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *) buf;
    size_t left = len;
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        left -= (size_t) n;
    }
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls == NULL) return JNI_ERR;
    g_illegal_state = (jclass) (*env)->NewGlobalRef(env, cls);
    return JNI_VERSION_1_6;
}

/*
 * create(cmd, env, cwd, rows, cols) -> int[2]{pid, masterFd} or NULL
 * cmd/env are argv/envp arrays (no shell); child becomes session leader with
 * the pty as its controlling terminal.
 */
JNIEXPORT jintArray JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_create(
        JNIEnv *env, jclass clazz,
        jobjectArray cmd, jobjectArray envArr, jstring cwd,
        jint rows, jint cols) {

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) { LOGW("posix_openpt failed: %s", strerror(errno)); return NULL; }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        LOGW("grantpt/unlockpt failed: %s", strerror(errno));
        close(master);
        return NULL;
    }

    const char *cwd_utf = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (cwd_utf == NULL) { close(master); return NULL; }
    char *cwd_copy = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);

    int argc = (*env)->GetArrayLength(env, cmd);
    int envc = (*env)->GetArrayLength(env, envArr);

    // Build argv/envp before forking (JNI in child after fork is unsafe).
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    char **envp = calloc((size_t) envc + 1, sizeof(char *));
    int i;
    for (i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, cmd, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    for (i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, envArr, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGW("fork failed: %s", strerror(errno));
        for (i = 0; i < argc; i++) free(argv[i]);
        for (i = 0; i < envc; i++) free(envp[i]);
        free(argv);
        free(envp);
        free(cwd_copy);
        close(master);
        return NULL;
    }

    if (pid == 0) {
        // ---- child ----
        // Resolve the slave path BEFORE closing the master: ptsname_r()
        // requires a valid master fd.
        char slave_path[128];
        if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) _exit(127);
        setsid();
        int slave = open(slave_path, O_RDWR | O_NOCTTY);
        if (slave < 0) _exit(127);
        close(master);
        // Session leader without a controlling terminal: acquire it explicitly.
        ioctl(slave, TIOCSCTTY, NULL);

        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
        ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
        ioctl(slave, TIOCSWINSZ, &ws);

        struct termios tio;
        if (tcgetattr(slave, &tio) == 0) {
            tio.c_iflag |= ICRNL | IXON;
            tio.c_oflag |= OPOST | ONLCR;
            tio.c_lflag |= ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
            tcsetattr(slave, TCSANOW, &tio);
        }

        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);

        if (cwd_copy != NULL && cwd_copy[0] != '\0') {
            chdir(cwd_copy);
        }

        // Restore default signal dispositions (Android Zygote masks/ignores some)
        signal(SIGPIPE, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        setenv("TMPDIR", "/tmp", 0);

        execve(argv[0], argv, envp);
        const char *err = strerror(errno);
        write_all(2, "\r\n[noxs-pty] execve failed: ", 28);
        if (argv[0]) write_all(2, argv[0], strlen(argv[0]));
        write_all(2, " (", 2);
        write_all(2, err, strlen(err));
        write_all(2, ")\r\n", 3);
        _exit(127); // execve failed
    }

    // ---- parent ----
    for (i = 0; i < argc; i++) free(argv[i]);
    for (i = 0; i < envc; i++) free(envp[i]);
    free(argv);
    free(envp);
    free(cwd_copy);

    g_master_fd = master;
    g_child_pid = pid;

    // Return a 1D jintArray {pid, masterFd} matching NativePty.create(...): IntArray?
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) {
        close(master);
        return NULL;
    }
    jint out[2];
    out[0] = (jint) pid;
    out[1] = (jint) master;
    (*env)->SetIntArrayRegion(env, result, 0, 2, out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_setSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(fd, TIOCSWINSZ, &ws);
    // Deliver SIGWINCH indirectly: TIOCSWINSZ signals the foreground pg.
}

JNIEXPORT jint JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_waitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_sendSignal(
        JNIEnv *env, jclass clazz, jint pid, jint signal) {
    kill((pid_t) pid, (int) signal);
}

JNIEXPORT void JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    close((int) fd);
}

JNIEXPORT jint JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_readBytes(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *cbuf = (*env)->GetByteArrayElements(env, buf, NULL);
    ssize_t n = read((int) fd, cbuf + off, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, buf, cbuf, 0);
    if (n < 0 && errno == EINTR) return 0;
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_noxs_linux_terminal_emulator_NativePty_writeBytes(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *cbuf = (*env)->GetByteArrayElements(env, data, NULL);
    int rc = write_all((int) fd, cbuf, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, data, cbuf, JNI_ABORT);
    return rc == 0 ? len : -1;
}
