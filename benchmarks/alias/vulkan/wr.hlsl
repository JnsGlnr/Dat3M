// dxc -T cs_6_0 -E main -spirv -fspv-target-env=vulkan1.3 -Fo wr.spv wr.hlsl

[[vk::binding(0)]] RWStructuredBuffer<int> ptrA;
[[vk::binding(1)]] RWStructuredBuffer<int> ptrB;
[[vk::binding(2)]] RWStructuredBuffer<int> output;

[numthreads(256, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint idx = tid.x;
    ptrA[idx] = idx;
    int val = ptrB[idx];
    output[idx] = 2 * idx - val;
}
