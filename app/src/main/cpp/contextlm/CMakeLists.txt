cmake_minimum_required(VERSION 3.22)

# Expects FUTO's ported ggml/llama.cpp & sentencepiece sources placed under:
#   app/src/main/cpp/contextlm/ggml/
#   app/src/main/cpp/contextlm/sentencepiece/
# (copied from futo-org/android-keyboard/native/jni/src/ggml and src/sentencepiece)

set(CONTEXTLM_SOURCES
    contextlm_jni.cpp
    ggml/LanguageModel.cpp
    ggml/ModelMeta.cpp
    ggml/common.cpp
    ggml/context.cpp
    ggml/ggml.c
    ggml/ggml-alloc.c
    ggml/ggml-backend.c
    ggml/ggml-quants.c
    ggml/llama.cpp
)

# Add SentencePiece sources if present
file(GLOB_RECURSE SENTENCEPIECE_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/sentencepiece/*.cc"
    "${CMAKE_CURRENT_SOURCE_DIR}/sentencepiece/*.cpp"
)

add_library(contextlm SHARED
    ${CONTEXTLM_SOURCES}
    ${SENTENCEPIECE_SOURCES}
)

target_include_directories(contextlm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml
    ${CMAKE_CURRENT_SOURCE_DIR}/sentencepiece
)

target_compile_options(contextlm PRIVATE
    -O3
    -DNDEBUG
    -fexceptions
    -DHAVE_PTHREAD
    -fvisibility=hidden
    -Wno-unused-parameter
    -Wno-unused-function
)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(contextlm PRIVATE -mfpu=neon)
endif()

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(contextlm
    ${log-lib}
    ${dl-lib}
    -Wl,-z,max-page-size=16384
)
