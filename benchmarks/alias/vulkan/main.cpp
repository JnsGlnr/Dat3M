// g++ -std=c++17 -O2 main.cpp -o vulkan -lvulkan

#include <vulkan/vulkan.h>
#include <cstring>
#include <string>
#include <iostream>
#include <vector>
#include <fstream>
#include <stdexcept>

static const int wgSize = 256;

static std::string shader = "";
static int deviceId = 0;

static VkInstance instance = VK_NULL_HANDLE;
static VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
static VkDevice device = VK_NULL_HANDLE;
static VkQueue queue = VK_NULL_HANDLE;
static uint32_t queueFamilyIndex = -1;

#define CHECK_VK(call) \
    do { \
        VkResult res = call; \
        if (res != VK_SUCCESS) { \
            std::cerr << "Vulkan error " << res << " at " << __FILE__ << ":" << __LINE__ << "\n"; \
            exit(1); \
        } \
    } while (0)

static void initVulkanCompute() {
    // Create Vulkan instance
    VkApplicationInfo appInfo = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    appInfo.pApplicationName = "Vulkan Compute";
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo createInfo = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    createInfo.pApplicationInfo = &appInfo;

    CHECK_VK(vkCreateInstance(&createInfo, nullptr, &instance));

    // Select physical device
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        throw std::runtime_error("Failed to find GPUs with Vulkan support!");
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    physicalDevice = devices[deviceId];

    VkPhysicalDeviceProperties deviceProps;
    vkGetPhysicalDeviceProperties(physicalDevice, &deviceProps);
    std::cout << "Selected physical device: " << deviceProps.deviceName << "\n";

    // Find queue family index
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());

    for (uint32_t i = 0; i < queueFamilyCount; ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            queueFamilyIndex = i;
            break;
        }
    }

    if (queueFamilyIndex == -1) {
        throw std::runtime_error("Failed to find a compute queue family!");
    }

    // Create logical device and retrieve queue
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueCreateInfo.queueFamilyIndex = queueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    const char* deviceExtensions[] = {VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME};
    VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR executableFeatures = {};
    executableFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_EXECUTABLE_PROPERTIES_FEATURES_KHR;
    executableFeatures.pipelineExecutableInfo = VK_TRUE;

    VkDeviceCreateInfo deviceCreateInfo = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceCreateInfo.pNext = &executableFeatures;
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
    deviceCreateInfo.enabledExtensionCount = 1;
    deviceCreateInfo.ppEnabledExtensionNames = deviceExtensions;

    CHECK_VK(vkCreateDevice(physicalDevice, &deviceCreateInfo, nullptr, &device));
    vkGetDeviceQueue(device, queueFamilyIndex, 0, &queue);

    std::cout << "Vulkan Compute Device initialized successfully.\n";
}

static uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    throw std::runtime_error("Failed to find suitable memory type!");
}

static std::vector<char> readFile(const std::string& filename) {
    std::ifstream file(filename, std::ios::ate | std::ios::binary);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open file: " + filename);
    }
    size_t fileSize = (size_t)file.tellg();
    std::vector<char> buffer(fileSize);
    file.seekg(0);
    file.read(buffer.data(), fileSize);
    file.close();
    return buffer;
}

std::string extractFilename(const std::string& filepath) {
    size_t lastSlash = filepath.find_last_of('/');
    std::string filename = (lastSlash == std::string::npos) ?
                           filepath :
                           filepath.substr(lastSlash + 1);
    size_t lastDot = filename.find_last_of('.');
    if (lastDot != std::string::npos && lastDot != 0) {
        filename = filename.substr(0, lastDot);
    }
    return filename;
}

static void logExecutable(VkPipeline pipeline) {
    auto pfnGetPipelineExecutablePropertiesKHR =
        (PFN_vkGetPipelineExecutablePropertiesKHR)vkGetDeviceProcAddr(device, "vkGetPipelineExecutablePropertiesKHR");
    auto pfnGetPipelineExecutableInternalRepresentationsKHR =
        (PFN_vkGetPipelineExecutableInternalRepresentationsKHR)vkGetDeviceProcAddr(device, "vkGetPipelineExecutableInternalRepresentationsKHR");

    if (!pfnGetPipelineExecutablePropertiesKHR || !pfnGetPipelineExecutableInternalRepresentationsKHR) {
        std::cerr << "Failed to load pipeline executable extension functions.\n";
        return;
    }

    VkPipelineInfoKHR pipeInfo = {VK_STRUCTURE_TYPE_PIPELINE_INFO_KHR};
    pipeInfo.pipeline = pipeline;

    uint32_t executableCount = 0;
    pfnGetPipelineExecutablePropertiesKHR(device, &pipeInfo, &executableCount, nullptr);

    std::vector<VkPipelineExecutablePropertiesKHR> executableProps(executableCount, {VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_PROPERTIES_KHR});
    pfnGetPipelineExecutablePropertiesKHR(device, &pipeInfo, &executableCount, executableProps.data());

    for (uint32_t i = 0; i < executableCount; i++) {
        VkPipelineExecutableInfoKHR execInfo = {VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INFO_KHR};
        execInfo.pipeline = pipeline;
        execInfo.executableIndex = i;

        uint32_t repCount = 0;
        pfnGetPipelineExecutableInternalRepresentationsKHR(device, &execInfo, &repCount, nullptr);

        if (repCount == 0) {
            std::cerr << "No representations for executable " << i << "\n";
            continue;
        }

        std::vector<VkPipelineExecutableInternalRepresentationKHR> representations(repCount,
            {VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INTERNAL_REPRESENTATION_KHR});
        pfnGetPipelineExecutableInternalRepresentationsKHR(device, &execInfo, &repCount, representations.data());

        for (auto& rep : representations) {
            rep.pData = malloc(rep.dataSize);
        }

        pfnGetPipelineExecutableInternalRepresentationsKHR(device, &execInfo, &repCount, representations.data());

        int j = 0;
        for (auto& rep : representations) {
            std::string filename = extractFilename(shader)
                                   + "-" + std::to_string(i) + "-" + std::to_string(j++)
                                   + (rep.isText ? ".txt" : ".bin");
            std::ofstream outFile(filename, std::ios::out | std::ios::binary);
            if (outFile.is_open()) {
                if (rep.isText) {
                    outFile << "Representation: " << rep.name << "\n";
                    outFile.write(static_cast<const char*>(rep.pData), rep.dataSize);
                    outFile << "\n";
                } else {
                    outFile.write(static_cast<const char*>(rep.pData), rep.dataSize);
                }
                outFile.close();
                std::cout << "Binary " << filename << " (" << rep.name << ")\n";
            } else {
                std::cerr << "Failed to open file: " << filename << "\n";
            }
            free(rep.pData);
        }
    }
}

static void runVulkanCompute() {
    const VkDeviceSize bufferSize = wgSize * sizeof(int);

    // Create aliased buffers
    VkBufferCreateInfo bufferInfo = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkBuffer bufferA, bufferB;
    CHECK_VK(vkCreateBuffer(device, &bufferInfo, nullptr, &bufferA));
    CHECK_VK(vkCreateBuffer(device, &bufferInfo, nullptr, &bufferB));

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device, bufferA, &memReqs);

    VkMemoryAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    VkDeviceMemory aliasedMemory;
    CHECK_VK(vkAllocateMemory(device, &allocInfo, nullptr, &aliasedMemory));

    void* data;
    vkMapMemory(device, aliasedMemory, 0, memReqs.size, 0, &data);
    memset(data, 0, (size_t)memReqs.size);

    CHECK_VK(vkBindBufferMemory(device, bufferA, aliasedMemory, 0));
    CHECK_VK(vkBindBufferMemory(device, bufferB, aliasedMemory, 0));

    // Create output buffer
    VkBuffer outputBuffer;
    CHECK_VK(vkCreateBuffer(device, &bufferInfo, nullptr, &outputBuffer));

    vkGetBufferMemoryRequirements(device, outputBuffer, &memReqs);
    VkMemoryAllocateInfo outAllocInfo = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    outAllocInfo.allocationSize = memReqs.size;
    outAllocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    VkDeviceMemory outputMemory;
    CHECK_VK(vkAllocateMemory(device, &outAllocInfo, nullptr, &outputMemory));
    CHECK_VK(vkBindBufferMemory(device, outputBuffer, outputMemory, 0));

    // Create descriptor set layout
    std::vector<VkDescriptorSetLayoutBinding> bindings(3);
    for (int i = 0; i < 3; i++) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo layoutInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings.data();

    VkDescriptorSetLayout descriptorSetLayout;
    CHECK_VK(vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &descriptorSetLayout));

    // Create descriptor pool and descriptor set
    VkDescriptorPoolSize poolSize = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3};
    VkDescriptorPoolCreateInfo poolInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;

    VkDescriptorPool descriptorPool;
    CHECK_VK(vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool));

    VkDescriptorSetAllocateInfo allocSetInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocSetInfo.descriptorPool = descriptorPool;
    allocSetInfo.descriptorSetCount = 1;
    allocSetInfo.pSetLayouts = &descriptorSetLayout;

    VkDescriptorSet descriptorSet;
    CHECK_VK(vkAllocateDescriptorSets(device, &allocSetInfo, &descriptorSet));

    // Update descriptor set with the actual buffers
    VkDescriptorBufferInfo bInfoA = {bufferA, 0, bufferSize};
    VkDescriptorBufferInfo bInfoB = {bufferB, 0, bufferSize};
    VkDescriptorBufferInfo bInfoOut = {outputBuffer, 0, bufferSize};

    std::vector<VkWriteDescriptorSet> descriptorWrites(3);
    for (int i = 0; i < 3; i++) {
        descriptorWrites[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        descriptorWrites[i].dstSet = descriptorSet;
        descriptorWrites[i].dstBinding = i;
        descriptorWrites[i].dstArrayElement = 0;
        descriptorWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        descriptorWrites[i].descriptorCount = 1;
    }
    descriptorWrites[0].pBufferInfo = &bInfoA;
    descriptorWrites[1].pBufferInfo = &bInfoB;
    descriptorWrites[2].pBufferInfo = &bInfoOut;

    vkUpdateDescriptorSets(device, 3, descriptorWrites.data(), 0, nullptr);

    // Create compute pipeline
    auto shaderCode = readFile(shader);
    VkShaderModuleCreateInfo createInfo = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    createInfo.codeSize = shaderCode.size();
    createInfo.pCode = reinterpret_cast<const uint32_t*>(shaderCode.data());

    VkShaderModule computeShaderModule;
    CHECK_VK(vkCreateShaderModule(device, &createInfo, nullptr, &computeShaderModule));

    VkPipelineShaderStageCreateInfo shaderStageInfo = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    shaderStageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStageInfo.module = computeShaderModule;
    shaderStageInfo.pName = "main";

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout;

    VkPipelineLayout pipelineLayout;
    CHECK_VK(vkCreatePipelineLayout(device, &pipelineLayoutInfo, nullptr, &pipelineLayout));

    VkComputePipelineCreateInfo pipelineInfo = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.flags = VK_PIPELINE_CREATE_CAPTURE_INTERNAL_REPRESENTATIONS_BIT_KHR |
                         VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR;
    pipelineInfo.stage = shaderStageInfo;
    pipelineInfo.layout = pipelineLayout;

    VkPipeline computePipeline;
    CHECK_VK(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &computePipeline));

    logExecutable(computePipeline);

    // Create command
    VkCommandPoolCreateInfo cmdPoolInfo = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cmdPoolInfo.queueFamilyIndex = queueFamilyIndex;

    VkCommandPool commandPool;
    CHECK_VK(vkCreateCommandPool(device, &cmdPoolInfo, nullptr, &commandPool));

    VkCommandBufferAllocateInfo cmdAllocInfo = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cmdAllocInfo.commandPool = commandPool;
    cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAllocInfo.commandBufferCount = 1;

    VkCommandBuffer cmd;
    CHECK_VK(vkAllocateCommandBuffers(device, &cmdAllocInfo, &cmd));

    VkCommandBufferBeginInfo beginInfo = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    CHECK_VK(vkBeginCommandBuffer(cmd, &beginInfo));

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);

    vkCmdDispatch(cmd, 1, 1, 1);

    CHECK_VK(vkEndCommandBuffer(cmd));

    // Submit and wait
    VkSubmitInfo submitInfo = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;

    CHECK_VK(vkQueueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE));
    CHECK_VK(vkQueueWaitIdle(queue));

    // Check results
    int* mappedData = nullptr;
    CHECK_VK(vkMapMemory(device, outputMemory, 0, bufferSize, 0, (void**)&mappedData));

    bool success = true;
    for (int i = 0; i < wgSize; ++i) {
        if (mappedData[i] != i) {
            std::cout << "Mismatch at thread " << i << ": expected " << i << ", got " << mappedData[i] << "\n";
            success = false;
        }
    }

    if (success) {
        std::cout << "Verification passed! All aliased reads matched.\n";
    }

    vkUnmapMemory(device, aliasedMemory);
    vkUnmapMemory(device, outputMemory);

    // Cleanup
    vkDestroyCommandPool(device, commandPool, nullptr);
    vkDestroyPipeline(device, computePipeline, nullptr);
    vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    vkDestroyShaderModule(device, computeShaderModule, nullptr);
    vkDestroyDescriptorPool(device, descriptorPool, nullptr);
    vkDestroyDescriptorSetLayout(device, descriptorSetLayout, nullptr);

    vkDestroyBuffer(device, bufferA, nullptr);
    vkDestroyBuffer(device, bufferB, nullptr);
    vkDestroyBuffer(device, outputBuffer, nullptr);

    vkFreeMemory(device, aliasedMemory, nullptr);
    vkFreeMemory(device, outputMemory, nullptr);
}

static void cleanupVulkanCompute() {
    if (device != VK_NULL_HANDLE) {
        vkDestroyDevice(device, nullptr);
    }
    if (instance != VK_NULL_HANDLE) {
        vkDestroyInstance(instance, nullptr);
    }
}

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
    if (parseArgs(argc, argv)) {
        return 1;
    }
    try {
        initVulkanCompute();
        runVulkanCompute();
        cleanupVulkanCompute();
    } catch (const std::exception& e) {
        std::cerr << "Fatal error: " << e.what() << "\n";
        return 1;
    }
    return 0;
}
