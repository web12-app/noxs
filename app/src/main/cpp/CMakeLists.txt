# Noxs native PTY bridge — minimal CMake config (original code)
cmake_minimum_required(VERSION 3.22.1)
project("noxs-pty" C)

add_library(noxs-pty SHARED noxs-pty.c)

target_link_libraries(noxs-pty
        android
        log)

# Stricter build for a security-sensitive component.
target_compile_options(noxs-pty PRIVATE
        -Wall -Wextra -Werror -fvisibility=hidden -fstack-protector-strong)
