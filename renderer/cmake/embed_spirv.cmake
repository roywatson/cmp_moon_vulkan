if(NOT DEFINED INPUT OR NOT DEFINED OUTPUT OR NOT DEFINED NAME)
    message(FATAL_ERROR "INPUT, OUTPUT, and NAME are required")
endif()

# Keep generated SPIR-V out of source control while still embedding it in the shared object.
file(READ "${INPUT}" bytes HEX)
string(LENGTH "${bytes}" byte_length)
math(EXPR remainder "${byte_length} % 8")
if(NOT remainder EQUAL 0)
    message(FATAL_ERROR "SPIR-V input is not 32-bit aligned")
endif()

set(words "")
math(EXPR last "${byte_length} - 8")
foreach(offset RANGE 0 ${last} 8)
    string(SUBSTRING "${bytes}" ${offset} 8 word)
    # SPIR-V is little endian; C++ uint32_t constants need the byte order reversed.
    string(SUBSTRING "${word}" 0 2 b0)
    string(SUBSTRING "${word}" 2 2 b1)
    string(SUBSTRING "${word}" 4 2 b2)
    string(SUBSTRING "${word}" 6 2 b3)
    string(APPEND words "0x${b3}${b2}${b1}${b0}u, ")
endforeach()
file(WRITE "${OUTPUT}" "#pragma once\n#include <cstdint>\nconstexpr uint32_t ${NAME}[] = { ${words} };\n")
