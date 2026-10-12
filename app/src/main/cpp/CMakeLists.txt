cmake_minimum_required(VERSION 3.22.1)
project(neoemu CXX)

add_library(neoemu SHARED frontend.cpp)
target_compile_options(neoemu PRIVATE -O3 -Wall)
target_link_libraries(neoemu android log aaudio dl)
