cmake_minimum_required(VERSION 3.22.1)
project("t9gamepad" C)

find_library(log-lib log)

# ── Librería JNI principal ──
add_library(gamepad SHARED gamepad.c fd_receiver.c)
target_link_libraries(gamepad ${log-lib})
target_compile_options(gamepad PRIVATE -Wall -O2)

# ── Helper daemon ejecutable ──
add_executable(uinput_helper uinput_helper.c)
target_compile_options(uinput_helper PRIVATE -Wall -O2)
target_link_options(uinput_helper PRIVATE -static)

set_target_properties(uinput_helper PROPERTIES
    OUTPUT_NAME "uinput_helper"
    SUFFIX ".so"
    PREFIX "lib"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
