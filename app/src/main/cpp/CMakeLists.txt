# Noxs native PTY bridge — minimal CMake config (original code)
cmake_minimum_required(VERSION 3.22.1)
project("noxs-pty" C)

add_library(noxs-pty SHARED noxs-pty.c)

target_link_libraries(noxs-pty
        android
        log)

# Stricter build for a security-sensitive component. JNI callbacks receive
# env/clazz parameters they legitimately never use, so disable that one check.
target_compile_options(noxs-pty PRIVATE
        -Wall -Wextra -Werror -Wno-unused-parameter
        -fvisibility=hidden -fstack-protector-strong)
