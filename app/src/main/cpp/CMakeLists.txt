cmake_minimum_required(VERSION 3.22.1)
project("netflix_torrent")

# Baixa e compila o libtorrent
include(FetchContent)

FetchContent_Declare(
    libtorrent
    GIT_REPOSITORY https://github.com/arvidn/libtorrent.git
    GIT_TAG v2.0.9
    GIT_SHALLOW TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static library")
set(static_libraries ON CACHE BOOL "Build static library")
set(libtorrent_build_tools OFF CACHE BOOL "Disable tools")
set(libtorrent_build_examples OFF CACHE BOOL "Disable examples")
set(libtorrent_build_tests OFF CACHE BOOL "Disable tests")
set(libtorrent_use_openssl OFF CACHE BOOL "Disable OpenSSL")
set(libtorrent_use_libiconv OFF CACHE BOOL "Disable iconv")
set(dht ON CACHE BOOL "Enable DHT")
set(encryption ON CACHE BOOL "Enable encryption")
set(logging OFF CACHE BOOL "Disable logging")

FetchContent_MakeAvailable(libtorrent)

# Biblioteca nativa
add_library(torrent_bridge SHARED
    torrent_bridge.cpp
)

target_include_directories(torrent_bridge PRIVATE
    ${libtorrent_SOURCE_DIR}/include
    ${libtorrent_BINARY_DIR}/include
)

target_link_libraries(torrent_bridge
    torrent::torrent
    ${log-lib}
)
