cmake_minimum_required(VERSION 3.22.1)
project("agor_voice")

# Whisper.cpp is checked out to apps/agor-android/app/src/main/cpp/whisper.cpp at build time.
# We don't pin a specific commit here — see scripts/sync-whisper.sh.
#
# If the directory is missing, build a no-op stub so the JNI lib still links and the
# Kotlin side can fall back to Android's SpeechRecognizer.

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

if(EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_NO_AVX ON CACHE BOOL "" FORCE)
    set(WHISPER_NO_AVX2 ON CACHE BOOL "" FORCE)
    set(WHISPER_NO_F16C ON CACHE BOOL "" FORCE)
    set(WHISPER_NO_FMA ON CACHE BOOL "" FORCE)
    add_subdirectory(${WHISPER_DIR} whisper-build EXCLUDE_FROM_ALL)
    set(HAVE_WHISPER ON)
else()
    set(HAVE_WHISPER OFF)
endif()

add_library(agor_voice SHARED whisper_jni.cpp)

if(HAVE_WHISPER)
    target_link_libraries(agor_voice PRIVATE whisper log android)
    target_compile_definitions(agor_voice PRIVATE HAVE_WHISPER=1)
else()
    target_link_libraries(agor_voice PRIVATE log android)
    message(WARNING "whisper.cpp not found at ${WHISPER_DIR} — building no-op stub. Run scripts/sync-whisper.sh to enable on-device transcription.")
endif()
