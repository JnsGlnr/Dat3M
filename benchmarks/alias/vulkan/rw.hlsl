// dxc -T cs_6_0 -E main -spirv -fspv-target-env=vulkan1.3 -Fo rw.spv rw.hlsl

[[vk::binding(0)]] RWStructuredBuffer<int> ptrA;
[[vk::binding(1)]] RWStructuredBuffer<int> ptrB;
[[vk::binding(2)]] RWStructuredBuffer<int> output;

[numthreads(256, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint idx = tid.x;
    int val = ptrA[idx];
    ptrB[idx] = idx;
    output[idx] = idx + val;
}
