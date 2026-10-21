cmake_minimum_required(VERSION 3.22.1)
project(fporunner C)
add_library(fporunner SHARED runner.c)
target_link_libraries(fporunner dl log)
