cmake_minimum_required(VERSION 3.22)
project("lxchat_native" LANGUAGES C CXX)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../thirdparty/llama.cpp" CACHE PATH "llama.cpp source directory")
get_filename_component(LLAMA_CPP_DIR "${LLAMA_CPP_DIR}" REALPATH)

add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${LLAMA_CPP_DIR}=llama.cpp
    -g0
    -fno-ident
)

set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT "unknown")
set(LLAMA_INSTALL_VERSION "0.0.0")
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# llama.cpp sources are still pulled in: the downloadable lxchat_llama.so is
# built from this same CMakeLists.txt by CI (see .github/workflows/build.yml
# job `build-native-llama`), which invokes `cmake --build --target lxchat_llama`.
# The target is marked EXCLUDE_FROM_ALL below so the default Gradle build
# (which calls `cmake --build .` with no explicit target) does NOT compile it,
# keeping the APK ~5-8MB smaller. The .so ships as a Release Asset and is
# System.load()ed at runtime from filesDir/native/lxchat_llama/ (see
# LlamaEngine.loadNative / LlamaChatEngine companion init).
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)
add_subdirectory(${LLAMA_CPP_DIR}/common ${CMAKE_CURRENT_BINARY_DIR}/common)
add_subdirectory(${LLAMA_CPP_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd)

foreach(cli llama-mtmd-cli llama-mtmd-debug
         llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        set_target_properties(${cli} PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
endforeach()
foreach(cli llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        target_include_directories(${cli} PRIVATE ${LLAMA_CPP_DIR}/common)
    endif()
endforeach()
if(TARGET llama-mtmd-cli)
    target_include_directories(llama-mtmd-cli PRIVATE ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()
if(TARGET llama-mtmd-debug)
    target_include_directories(llama-mtmd-debug PRIVATE ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()

target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="unknown")

# ── llama JNI wrapper (downloadable component, NOT in APK) ───────────
# EXCLUDE_FROM_ALL keeps it out of the default Gradle build. CI builds it
# explicitly via `cmake --build --target lxchat_llama` and uploads the .so
# as a Release Asset.
add_library(lxchat_llama SHARED llama_jni.cpp llama_chat_jni.cpp)
set_target_properties(lxchat_llama PROPERTIES EXCLUDE_FROM_ALL TRUE)
target_link_libraries(lxchat_llama llama mtmd log)
target_include_directories(lxchat_llama PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/tools/mtmd)
target_link_options(lxchat_llama PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all -Wl,--threads=1
)

# ── objcopy for reproducible builds ──────────────────────
find_program(LLVM_OBJCOPY llvm-objcopy
    HINTS ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin NO_DEFAULT_PATH
)

# ── Minimal JNI stub for proot ───────────────────────────
# System.loadLibrary("lxchat_proot") triggers APK extraction of native libs.
# Actual proot execution uses ProcessBuilder on libproot_exec.so (GNUmakefile-built).
add_library(lxchat_proot SHARED proot_jni.cpp)
target_link_options(lxchat_proot PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all
)

# ── Redemption code native validator ─────────────────────
# Self-contained HMAC-SHA256 + base64 + expiry check; no external deps.
add_library(redemption_native SHARED redemption_native.cpp)
target_link_libraries(redemption_native log)
target_link_options(redemption_native PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all
)

# ── Reproducible: strip .comment ─────────────────────────
if(DEFINED ENV{SOURCE_DATE_EPOCH})
    add_definitions(-DSOURCE_DATE_EPOCH=$ENV{SOURCE_DATE_EPOCH})
endif()
if(LLVM_OBJCOPY)
    add_custom_command(TARGET lxchat_llama POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:lxchat_llama>
    )
    add_custom_command(TARGET lxchat_proot POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:lxchat_proot>
    )
    add_custom_command(TARGET redemption_native POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:redemption_native>
    )
endif()
