# CMake describe cómo construir el pequeño puente entre Kotlin y Whisper.
# Android Studio ejecutará estas instrucciones cuando Nowel compile el proyecto.
cmake_minimum_required(VERSION 3.22.1)
project(agenda_voz_local LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Se fija una versión de la biblioteca; no se sigue una rama que cambie cada día.
# La descarga es una dependencia de compilación en el PC, no una petición desde el celular.
include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Bibliotecas internas estáticas" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "No incluir pruebas de Whisper" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "No incluir ejemplos de Whisper" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "No incluir servidor" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "Sin descargas de modelos en ejecución" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "No usar instrucciones del PC para Android" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Sin biblioteca OpenMP adicional" FORCE)
set(GGML_CPU ON CACHE BOOL "Usar el procesador del teléfono" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "Una biblioteca de procesador" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Sin búsqueda de motores externos" FORCE)
set(GGML_METAL OFF CACHE BOOL "No usar Metal de Apple" FORCE)
set(GGML_CUDA OFF CACHE BOOL "No usar CUDA de NVIDIA" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "No asumir aceleración gráfica" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "No asumir OpenCL" FORCE)
set(GGML_BLAS OFF CACHE BOOL "Sin BLAS externo" FORCE)

FetchContent_Declare(
    whisper_local
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.8.3.tar.gz
)
FetchContent_MakeAvailable(whisper_local)

# JNI es el puente que permite llamar C++ desde Kotlin dentro de la misma app.
# Las bibliotecas internas se incorporan a este archivo de Android.
add_library(agenda_voz_local SHARED puente_whisper.cpp)
target_include_directories(agenda_voz_local PRIVATE "${whisper_local_SOURCE_DIR}/include")
target_link_libraries(agenda_voz_local PRIVATE whisper android log)

# Facilita la carga en dispositivos Android con páginas de memoria de 16 KB.
# El NDK que utiliza Android Studio debe ofrecer también una biblioteca C++ compatible.
target_link_options(agenda_voz_local PRIVATE "-Wl,-z,max-page-size=16384")
# El puente conserva sus bibliotecas C++ internas para no interferir con otros motores.
target_link_options(agenda_voz_local PRIVATE "-Wl,--exclude-libs,ALL")
