cmake_minimum_required(VERSION 3.22.1)
project(lumo_denoise C)

# RNNoise (BSD licence, see rnnoise/COPYING): neural-network noise suppression for voice.
file(GLOB RNNOISE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/*.c)
add_library(lumo_denoise SHARED denoise_jni.c ${RNNOISE_SRC})
target_include_directories(lumo_denoise PRIVATE rnnoise rnnoise/include)
target_compile_options(lumo_denoise PRIVATE -O3 -w)
target_link_libraries(lumo_denoise m)
