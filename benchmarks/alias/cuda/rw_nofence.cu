// nvcc -arch sm_70 -lcuda -o rw_nofence_sm_70.ptx --ptx rw_nofence.cu
// nvcc -arch sm_70 -lcuda -o rw_nofence_sm_70.out --cubin rw_nofence_sm_70.ptx
// nvdisasm rw_nofence_sm_70.out > rw_nofence_sm_70.asm
// ./cuda rw_nofence_sm_70.ptx

// RTX 40 series
// nvcc -arch sm_89 -lcuda -o rw_nofence_sm_89.ptx --ptx rw_nofence.cu
// nvcc -arch sm_89 -lcuda -o rw_nofence_sm_89.out --cubin rw_nofence_sm_89.ptx
// nvdisasm rw_nofence_sm_89.out > rw_nofence_sm_89.asm
// ./cuda rw_nofence_sm_89.ptx

// RTX 50 series
// nvcc -arch sm_90 -lcuda -o rw_nofence_sm_90.ptx --ptx rw_nofence.cu
// nvcc -arch sm_90 -lcuda -o rw_nofence_sm_90.out --cubin rw_nofence_sm_90.ptx
// nvdisasm rw_nofence_sm_90.out > rw_nofence_sm_90.asm
// ./cuda rw_nofence_sm_90.ptx

#include <cuda.h>

__global__ void kernel(int* ptrA, int* ptrB, int* output) {
    int tid = blockIdx.x * blockDim.x + threadIdx.x;
    int val = ptrA[tid];
    ptrB[tid] = tid;
    output[tid] = val + tid;
}