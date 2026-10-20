cmake_minimum_required(VERSION 3.18)
project(mp3enc C)

# Baixa o código-fonte do LAME 3.100 (LGPL) na hora do build.
include(FetchContent)
FetchContent_Declare(lame
    URL https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz)
FetchContent_GetProperties(lame)
if(NOT lame_POPULATED)
    FetchContent_Populate(lame)
endif()
set(L ${lame_SOURCE_DIR})

# Ajuste conhecido para compilar o LAME com o NDK.
file(READ ${L}/libmp3lame/util.h UTIL_H)
string(REPLACE "extern ieee754_float32_t fast_log2(ieee754_float32_t x);"
               "extern float fast_log2(float x);" UTIL_H "${UTIL_H}")
file(WRITE ${L}/libmp3lame/util.h "${UTIL_H}")

file(GLOB LAME_SRC ${L}/libmp3lame/*.c)
add_library(mp3enc SHARED mp3enc.c ${LAME_SRC})
target_include_directories(mp3enc PRIVATE ${L}/include ${L}/libmp3lame)
target_compile_definitions(mp3enc PRIVATE STDC_HEADERS=1 HAVE_STDINT_H=1 HAVE_LIMITS_H=1 HAVE_STRING_H=1 HAVE_ERRNO_H=1)
target_compile_options(mp3enc PRIVATE -O2 -w
    -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-pointer-types)
target_link_libraries(mp3enc m)
