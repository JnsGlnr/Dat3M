// nvcc -arch sm_70 -lcuda -o wr_nofence_sm_70.ptx --ptx wr_nofence.cu
// nvcc -arch sm_70 -lcuda -o wr_nofence_sm_70.out --cubin wr_nofence_sm_70.ptx
// nvdisasm wr_nofence_sm_70.out > wr_nofence_sm_70.asm
// ./cuda wr_nofence_sm_70.ptx

// RTX 40 series
// nvcc -arch sm_89 -lcuda -o wr_nofence_sm_89.ptx --ptx wr_nofence.cu
// nvcc -arch sm_90 -lcuda -o wr_nofence_sm_89.out --cubin wr_nofence_sm_89.ptx
// nvdisasm wr_nofence_sm_89.out > wr_nofence_sm_89.asm
// ./cuda wr_nofence_sm_89.ptx

// RTX 50 series
// nvcc -arch sm_90 -lcuda -o wr_nofence_sm_90.ptx --ptx wr_nofence.cu
// nvcc -arch sm_90 -lcuda -o wr_nofence_sm_90.out --cubin wr_nofence_sm_90.ptx
// nvdisasm wr_nofence_sm_90.out > wr_nofence_sm_90.asm
// ./cuda wr_nofence_sm_90.ptx

#include <cuda.h>

__global__ void kernel(int* ptrA, int* ptrB, int* output) {
    int tid = blockIdx.x * blockDim.x + threadIdx.x;
    ptrA[tid] = tid;
    int val = ptrB[tid];
    output[tid] = val;
}