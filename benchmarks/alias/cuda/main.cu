// nvcc -lcuda -o cuda main.cu

#include <cuda.h>
#include <cstring>
#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <iostream>
#include <stdexcept>

static const int wgSize = 256;

static std::string shader = "";
static int deviceId = 0;

#define CHECK_CU(call) \
    do { \
        CUresult res = call; \
        if (res != CUDA_SUCCESS) { \
            const char* msg; \
            cuGetErrorString(res, &msg); \
            printf("CU error at %s:%d: %s\n", __FILE__, __LINE__, msg); \
            exit(EXIT_FAILURE); \
        } \
    } while (0)

void printUsage(const char* program) {
    std::cerr << "Usage: " << program << " <shader.spv> [--device <device_id>]\n";
}

static int parseArgs(int argc, char* argv[]) {
    if (argc != 2 && argc != 4) {
        printUsage(argv[0]);
        return 1;
    }
    shader = argv[1];
    if (argc == 4) {
        std::string flag = argv[2];
        if (flag == "--device") {
            try {
                deviceId = std::stoi(argv[3]);
            } catch (const std::invalid_argument& e) {
                std::cerr << "Error: --device requires an integer, but got '" << argv[3] << "'\n";
                return 1;
            } catch (const std::out_of_range& e) {
                std::cerr << "Error: --device value is out of range.\n";
                return 1;
            }
        } else {
            std::cerr << "Error: Unknown flag '" << flag << "'\n";
            printUsage(argv[0]);
            return 1;
        }
    }
    return 0;
}

int main(int argc, char* argv[]) {
    parseArgs(argc, argv);

    // Create context
    CHECK_CU(cuInit(0));

    CUdevice cuDevice;
    CHECK_CU(cuDeviceGet(&cuDevice, deviceId));

    CUcontext cuCtx;
    CHECK_CU(cuCtxCreate(&cuCtx, 0, cuDevice));

    int vmmSupported = 0;
    CHECK_CU(cuDeviceGetAttribute(&vmmSupported, CU_DEVICE_ATTRIBUTE_VIRTUAL_MEMORY_MANAGEMENT_SUPPORTED, cuDevice));

    if (!vmmSupported) {
        printf("Virtual Memory Management is not supported on this device/toolkit.\n");
        CHECK_CU(cuCtxDestroy(cuCtx));
        return 0;
    }

    // Create aliased buffers
    CUmemAllocationProp prop = {};
    prop.type = CU_MEM_ALLOCATION_TYPE_PINNED;
    prop.location.type = CU_MEM_LOCATION_TYPE_DEVICE;
    prop.location.id = deviceId;

    size_t allocSize = 0;
    CHECK_CU(cuMemGetAllocationGranularity(&allocSize, &prop, CU_MEM_ALLOC_GRANULARITY_MINIMUM));

    CUmemGenericAllocationHandle allocHandle;
    CHECK_CU(cuMemCreate(&allocHandle, allocSize, &prop, 0));

    CUdeviceptr ptrA = 0ULL, ptrB = 0ULL;
    CHECK_CU(cuMemAddressReserve(&ptrA, allocSize, 0, 0, 0));
    CHECK_CU(cuMemAddressReserve(&ptrB, allocSize, 0, 0, 0));

    CHECK_CU(cuMemMap(ptrA, allocSize, 0, allocHandle, 0));
    CHECK_CU(cuMemMap(ptrB, allocSize, 0, allocHandle, 0));

    CUmemAccessDesc accessDesc = {};
    accessDesc.location.type = CU_MEM_LOCATION_TYPE_DEVICE;
    accessDesc.location.id = deviceId;
    accessDesc.flags = CU_MEM_ACCESS_FLAGS_PROT_READWRITE;

    CHECK_CU(cuMemSetAccess(ptrA, allocSize, &accessDesc, 1));
    CHECK_CU(cuMemSetAccess(ptrB, allocSize, &accessDesc, 1));

    // Create output buffer
    const size_t bufferSize = wgSize * sizeof(int);
    CUdeviceptr ptrOutput = 0ULL;
    CHECK_CU(cuMemAlloc(&ptrOutput, bufferSize));

    // Load kernel, launch, and wait
    printf("Launching the kernel...\n");
    CUmodule cudaModule;
    CHECK_CU(cuModuleLoad(&cudaModule, shader.c_str()));
    CUfunction kernelFunc;
    CHECK_CU(cuModuleGetFunction(&kernelFunc, cudaModule, "_Z6kernelPiS_S_"));

    void* args[] = {&ptrA, &ptrB, &ptrOutput};
    CHECK_CU(cuLaunchKernel(
        kernelFunc,
        1, 1, 1,    // Grid dimensions (X, Y, Z)
        256, 1, 1,  // Block dimensions (X, Y, Z)
        0,          // Shared memory size in bytes
        nullptr,    // Stream (nullptr = default stream)
        args,       // Kernel arguments
        nullptr     // Extra options
    ));

    CHECK_CU(cuCtxSynchronize());

    // Check results
    int* output = (int*)malloc(bufferSize);
    CHECK_CU(cuMemcpyDtoH(output, ptrOutput, bufferSize));

    bool success = true;
    for (int i = 0; i < wgSize; ++i) {
        if (output[i] != i) {
            printf("Mismatch at thread %d: expected %d, got %d\n", i, i, output[i]);
            success = false;
        }
    }

    if (success) {
        printf("Verification passed! All aliased reads matched the expected values.\n");
    }

    // Cleanup
    free(output);
    CHECK_CU(cuMemFree(ptrOutput));

    CHECK_CU(cuMemUnmap(ptrA, allocSize));
    CHECK_CU(cuMemUnmap(ptrB, allocSize));
    CHECK_CU(cuMemAddressFree(ptrA, allocSize));
    CHECK_CU(cuMemAddressFree(ptrB, allocSize));
    CHECK_CU(cuMemRelease(allocHandle));

    CHECK_CU(cuCtxDestroy(cuCtx));

    return 0;
}