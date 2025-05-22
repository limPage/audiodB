cmake_minimum_required(VERSION 3.22.1)
project("audiodb")

# webrtc VAD 소스 모음
file(GLOB VAD_SRC
        "webrtc/common_audio/vad/*.c"
        "webrtc/common_audio/signal_processing/*.c"
)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-vad.cpp
        ${VAD_SRC}
)

include_directories(
        ${CMAKE_SOURCE_DIR}
        ${CMAKE_SOURCE_DIR}/webrtc
        ${CMAKE_SOURCE_DIR}/webrtc/common_audio
        ${CMAKE_SOURCE_DIR}/webrtc/common_audio/vad
        ${CMAKE_SOURCE_DIR}/webrtc/common_audio/vad/include
        ${CMAKE_SOURCE_DIR}/webrtc/common_audio/signal_processing/include
)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME} ${log-lib})
