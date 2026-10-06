cmake_minimum_required(VERSION 3.10)
project(__PKG_NAME__ CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(__PKG_NAME__ src/main.cpp)
