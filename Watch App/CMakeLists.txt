cmake_minimum_required(VERSION 3.20.0)

set(BOARD esp32s3_devkitc/esp32s3/procpu CACHE STRING "Default Zephyr board")

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(minimal_zephyr_app)

target_sources(app PRIVATE
    src/main.c
    src/panel.c
)
