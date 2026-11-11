cmake_minimum_required(VERSION 3.22.1)
project(voicewhisper C CXX)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp (MIT licence) does the speech recognition. It is downloaded at a
# fixed version when the project is first built.
include(FetchContent)
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whispercpp)

add_library(voicewhisper SHARED whisper_jni.cpp)
target_link_libraries(voicewhisper PRIVATE whisper log)
# Phones with 16 KB memory pages (Android 15+) need this alignment.
target_link_options(voicewhisper PRIVATE "-Wl,-z,max-page-size=16384")
