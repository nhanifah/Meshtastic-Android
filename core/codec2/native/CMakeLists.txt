# Copyright (c) 2026 Meshtastic LLC
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# Builds libcodec2jni: the vendored Codec2 speech codec (static, LGPL-2.1) plus the JNI bridge for
# org.meshtastic.core.codec2.Codec2Native. Used by :core:codec2-jni (Android, through AGP) and by
# :core:codec2's desktop task (host CMake, JNI_INCLUDE_ROOT pointing at a JDK's include directory).
cmake_minimum_required(VERSION 3.22)
project(codec2jni C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(C2 ${CMAKE_CURRENT_SOURCE_DIR}/codec2)
add_library(codec2_vendored STATIC
    ${C2}/codec2.c ${C2}/codec2_fft.c ${C2}/kiss_fft.c ${C2}/kiss_fftr.c ${C2}/lpc.c ${C2}/nlp.c
    ${C2}/postfilter.c ${C2}/sine.c ${C2}/interp.c ${C2}/lsp.c ${C2}/mbest.c ${C2}/newamp1.c ${C2}/phase.c
    ${C2}/quantise.c ${C2}/pack.c
    ${C2}/codebook.c ${C2}/codebookd.c ${C2}/codebookjmv.c ${C2}/codebookge.c ${C2}/codebooknewamp1.c
    ${C2}/codebooknewamp1_energy.c)
target_include_directories(codec2_vendored PUBLIC ${C2})
# Upstream code; its warnings are not ours to fix.
if(MSVC)
    target_compile_options(codec2_vendored PRIVATE /w)
else()
    target_compile_options(codec2_vendored PRIVATE -w -fvisibility=hidden)
endif()
if(NOT MSVC)
    target_link_libraries(codec2_vendored PUBLIC m)
endif()

add_library(codec2jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/jni/codec2_jni.c)
target_link_libraries(codec2jni PRIVATE codec2_vendored)
if(NOT MSVC)
    target_compile_options(codec2jni PRIVATE -fvisibility=hidden -Wall)
endif()

if(NOT ANDROID)
    if(NOT JNI_INCLUDE_ROOT)
        message(FATAL_ERROR "Pass -DJNI_INCLUDE_ROOT=<JDK>/include for a desktop build")
    endif()
    if(APPLE)
        set(JNI_MD darwin)
    elseif(WIN32)
        set(JNI_MD win32)
    else()
        set(JNI_MD linux)
    endif()
    target_include_directories(codec2jni PRIVATE ${JNI_INCLUDE_ROOT} ${JNI_INCLUDE_ROOT}/${JNI_MD})
else()
    target_link_options(codec2jni PRIVATE -Wl,--gc-sections)
endif()
