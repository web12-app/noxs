# Noxs ProGuard/R8 rules (original)

# Keep the JNI bridge names — they are called from C by exact symbol name.
-keep class com.noxs.linux.terminal.emulator.NativePty { *; }

# MiniJson reflection-free but keep the manifest model shapes for logging.
-keep class com.noxs.linux.shared.BootstrapManifest { *; }
-keep class com.noxs.linux.shared.BootstrapArtifact { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Remove verbose logging in release builds of the shared logger calls
# (NoxsLog is a ring buffer, intentionally kept for diagnostics).
