cmake_minimum_required(VERSION 3.22)
project(leantype_contextlm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_ANDROID_STL_TYPE c++_static)

add_subdirectory(contextlm)
