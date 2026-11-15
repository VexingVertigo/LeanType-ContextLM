LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := contextlm
LOCAL_SRC_FILES := contextlm_jni.cpp ggml/LanguageModel.cpp ggml/ModelMeta.cpp ggml/common.cpp ggml/context.cpp ggml/ggml.c ggml/ggml-alloc.c ggml/ggml-backend.c ggml/ggml-quants.c ggml/llama.cpp sentencepiece/bpe_model.cc sentencepiece/char_model.cc sentencepiece/error.cc sentencepiece/filesystem.cc sentencepiece/model_factory.cc sentencepiece/model_interface.cc sentencepiece/normalizer.cc sentencepiece/sentencepiece_processor.cc sentencepiece/unigram_model.cc sentencepiece/util.cc sentencepiece/word_model.cc $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/sentencepiece/builtin_pb/*.cc)) $(subst $(LOCAL_PATH)/,,$(filter-out %io_win32.cc,$(wildcard $(LOCAL_PATH)/third_party/protobuf-lite/*.cc)))
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/ggml $(LOCAL_PATH)/sentencepiece $(LOCAL_PATH)/sentencepiece/builtin_pb $(LOCAL_PATH)/third_party/protobuf-lite
LOCAL_CFLAGS := -O3 -DNDEBUG -DHAVE_PTHREAD -D_USE_INTERNAL_STRING_VIEW -fvisibility=hidden -Wno-unused-parameter -Wno-unused-function
LOCAL_CPPFLAGS := -std=c++17 -fexceptions
LOCAL_LDLIBS := -llog -ldl -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
