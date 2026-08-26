// ============================================================================
//  TASK 1.1 + 2.2 — VULKAN RENDERER · trien khai DAY DU (Tuan 2)
//  Duong du lieu: MediaCodec -> ImageReader -> AHardwareBuffer --zero-copy-->
//  VkImage (YCbCr) -> Pass1 CST/Layer1 -> midA fp16 -> Pass2 LUT tetrahedral
//  -> midB fp16 -> [Compute scopes] -> Pass3 composite + overlay scope -> present
//  Cache theo external format: YCbCr conversion + pipeline CST chi dung lai khi
//  decoder doi dinh dang (thuong la KHONG doi) — het canh bao rebuild moi frame.
// ============================================================================
#include "vulkan_renderer.h"

#include <android/asset_manager.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <cstring>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FDC/Vk", __VA_ARGS__)
#define CHECK_VK(expr, msg)                                                    \
    do {                                                                       \
        VkResult r_ = (expr);                                                  \
        if (r_ != VK_SUCCESS) {                                                \
            if (err) *err = std::string(msg) + " (VkResult " + std::to_string(r_) + ")"; \
            LOGE("%s", err ? err->c_str() : msg);                              \
            return false;                                                      \
        }                                                                      \
    } while (0)

namespace fdc {

// ================================================================== init ====
bool VulkanRenderer::init(ANativeWindow* window, AAssetManager* assetMgr, std::string* err) {
    assets_ = assetMgr;
    if (!createInstance(err)) return false;

    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = window;
    CHECK_VK(vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_),
             "Khong tao duoc Android surface");

    if (!pickPhysicalDevice(err)) return false;
    if (!createDevice(err)) return false;

    const uint32_t w = uint32_t(ANativeWindow_getWidth(window));
    const uint32_t h = uint32_t(ANativeWindow_getHeight(window));
    if (!createSwapchain(w, h, err)) return false;
    if (!createIntermediateTargets(err)) return false;
    if (!createRenderPasses(err)) return false;
    if (!createFramebuffers(err)) return false;
    if (!createScopeBuffer(err)) return false;
    if (!createDescriptors(err)) return false;
    if (!createStaticPipelines(err)) return false;

    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpi.queueFamilyIndex = queueFamily_;
    CHECK_VK(vkCreateCommandPool(device_, &cpi, nullptr, &cmdPool_), "Loi command pool");

    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = cmdPool_;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    CHECK_VK(vkAllocateCommandBuffers(device_, &cai, &cmd_), "Loi command buffer");

    VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo feni{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    feni.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    CHECK_VK(vkCreateSemaphore(device_, &semi, nullptr, &semAcquire_), "Loi semaphore");
    CHECK_VK(vkCreateSemaphore(device_, &semi, nullptr, &semRender_),  "Loi semaphore");
    CHECK_VK(vkCreateFence(device_, &feni, nullptr, &fence_),          "Loi fence");

    // TASK E3 — placeholder watermark 1x1 trong suot: descriptor b1 cua composite
    // luon hop le; logo that duoc nap qua setWatermarkImage (JNI) khi nguoi dung bat.
    {
        const uint8_t clear[4] = {0, 0, 0, 0};
        if (!uploadWatermarkRgba(clear, 1, 1, err)) return false;
    }
    return uploadIdentityLut(err);   // LUT no-op de pipeline chay ngay khi chua nap .cube
}

bool VulkanRenderer::createInstance(std::string* err) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "FreediveColorStudio";
    app.apiVersion = VK_API_VERSION_1_3;
    const char* exts[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
    };
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = uint32_t(sizeof(exts) / sizeof(exts[0]));
    ici.ppEnabledExtensionNames = exts;
    CHECK_VK(vkCreateInstance(&ici, nullptr, &instance_), "Khong tao duoc VkInstance");
    return true;
}

bool VulkanRenderer::pickPhysicalDevice(std::string* err) {
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(instance_, &n, nullptr);
    if (n == 0) { if (err) *err = "May khong ho tro Vulkan"; return false; }
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(instance_, &n, devs.data());
    for (auto d : devs) {
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qs(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, qs.data());
        for (uint32_t i = 0; i < qn; i++) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(d, i, surface_, &present);
            if ((qs[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
                    (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT) && present) {
                physicalDevice_ = d; queueFamily_ = i; return true;
            }
        }
    }
    if (err) *err = "Khong tim thay queue graphics+compute+present";
    return false;
}

bool VulkanRenderer::createDevice(std::string* err) {
    const float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    const char* exts[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
    };
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeat{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES};
    ycbcrFeat.samplerYcbcrConversion = VK_TRUE;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &ycbcrFeat;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = uint32_t(sizeof(exts) / sizeof(exts[0]));
    dci.ppEnabledExtensionNames = exts;
    CHECK_VK(vkCreateDevice(physicalDevice_, &dci, nullptr, &device_), "Khong tao duoc VkDevice");
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    return true;
}

bool VulkanRenderer::createSwapchain(uint32_t w, uint32_t h, std::string* err) {
    VkSurfaceCapabilitiesKHR caps{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps);
    swapExtent_ = {w ? w : caps.currentExtent.width, h ? h : caps.currentExtent.height};

    VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = surface_;
    sci.minImageCount = caps.minImageCount + 1;
    sci.imageFormat = swapFormat_;
    sci.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    sci.imageExtent = swapExtent_;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    sci.preTransform = caps.currentTransform;
    sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    sci.clipped = VK_TRUE;
    CHECK_VK(vkCreateSwapchainKHR(device_, &sci, nullptr, &swapchain_), "Loi swapchain");

    uint32_t n = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    swapImages_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, swapImages_.data());
    swapViews_.resize(n);
    for (uint32_t i = 0; i < n; i++) {
        VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vci.image = swapImages_[i];
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format = swapFormat_;
        vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        CHECK_VK(vkCreateImageView(device_, &vci, nullptr, &swapViews_[i]), "Loi swap view");
    }
    return true;
}

bool VulkanRenderer::createFp16Image(VkImage& img, VkDeviceMemory& mem, VkImageView& view,
                                     std::string* err) {
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    ici.extent = {swapExtent_.width, swapExtent_.height, 1};
    ici.mipLevels = ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    CHECK_VK(vkCreateImage(device_, &ici, nullptr, &img), "Loi anh fp16");
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(device_, img, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &mem), "Loi VRAM fp16");
    vkBindImageMemory(device_, img, mem, 0);
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = img;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CHECK_VK(vkCreateImageView(device_, &vci, nullptr, &view), "Loi view fp16");
    return true;
}

bool VulkanRenderer::createIntermediateTargets(std::string* err) {
    if (!createFp16Image(midA_, midAMem_, midAView_, err)) return false;
    if (!createFp16Image(midB_, midBMem_, midBView_, err)) return false;
    if (!createFp16Image(midC_, midCMem_, midCView_, err)) return false;
    if (!linearSampler_) {
        VkSamplerCreateInfo smi{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        smi.magFilter = smi.minFilter = VK_FILTER_LINEAR;
        smi.addressModeU = smi.addressModeV = smi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        CHECK_VK(vkCreateSampler(device_, &smi, nullptr, &linearSampler_), "Loi linear sampler");
    }
    // UBO Layer 3 — host visible, map thuong truc (keyframing ghi moi frame)
    if (!l3Ubo_) {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = sizeof(Layer3Ubo);
        bci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        CHECK_VK(vkCreateBuffer(device_, &bci, nullptr, &l3Ubo_), "Loi UBO L3");
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device_, l3Ubo_, &req);
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &l3UboMem_), "Loi bo nho UBO L3");
        vkBindBufferMemory(device_, l3Ubo_, l3UboMem_, 0);
        CHECK_VK(vkMapMemory(device_, l3UboMem_, 0, sizeof(Layer3Ubo), 0, &l3UboMapped_),
                 "Loi map UBO L3");
    }
    return true;
}

bool VulkanRenderer::createRenderPasses(std::string* err) {
    // rpFp16: ve vao target fp16, ket thuc o SHADER_READ de pass sau sample
    VkAttachmentDescription att{};
    att.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    dep.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo rpi{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpi.attachmentCount = 1; rpi.pAttachments = &att;
    rpi.subpassCount = 1;    rpi.pSubpasses = &sub;
    rpi.dependencyCount = 1; rpi.pDependencies = &dep;
    CHECK_VK(vkCreateRenderPass(device_, &rpi, nullptr, &rpFp16_), "Loi render pass fp16");

    // rpSwap: composite -> present
    att.format = swapFormat_;
    att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    CHECK_VK(vkCreateRenderPass(device_, &rpi, nullptr, &rpSwap_), "Loi render pass swap");
    return true;
}

bool VulkanRenderer::createMidFramebuffers(std::string* err) {
    auto make = [&](VkImageView view, VkFramebuffer* out) -> bool {
        VkFramebufferCreateInfo fci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fci.renderPass = rpFp16_;
        fci.attachmentCount = 1;
        fci.pAttachments = &view;
        fci.width = swapExtent_.width;
        fci.height = swapExtent_.height;
        fci.layers = 1;
        CHECK_VK(vkCreateFramebuffer(device_, &fci, nullptr, out), "Loi framebuffer mid");
        return true;
    };
    if (!make(midAView_, &fbA_)) return false;
    if (!make(midBView_, &fbB_)) return false;
    if (!make(midCView_, &fbC_)) return false;
    return true;
}

bool VulkanRenderer::createFramebuffers(std::string* err) {
    if (!createMidFramebuffers(err)) return false;
    fbSwap_.resize(swapViews_.size());
    for (size_t i = 0; i < swapViews_.size(); i++) {
        VkFramebufferCreateInfo fci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fci.renderPass = rpSwap_;
        fci.attachmentCount = 1;
        fci.pAttachments = &swapViews_[i];
        fci.width = swapExtent_.width;
        fci.height = swapExtent_.height;
        fci.layers = 1;
        CHECK_VK(vkCreateFramebuffer(device_, &fci, nullptr, &fbSwap_[i]), "Loi framebuffer swap");
    }
    return true;
}

bool VulkanRenderer::createScopeBuffer(std::string* err) {
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = kScopeBufSize;
    bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    CHECK_VK(vkCreateBuffer(device_, &bci, nullptr, &scopeBuf_), "Loi scope SSBO");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, scopeBuf_, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &scopeMem_), "Loi bo nho scope");
    vkBindBufferMemory(device_, scopeBuf_, scopeMem_, 0);
    return true;
}

// =========================================================== descriptors ====
bool VulkanRenderer::createDescriptors(std::string* err) {
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 10},
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2},
    };
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    dpi.maxSets = 10;
    dpi.poolSizeCount = 3;
    dpi.pPoolSizes = sizes;
    CHECK_VK(vkCreateDescriptorPool(device_, &dpi, nullptr, &descPool_), "Loi descriptor pool");

    auto makeLayout = [&](std::vector<VkDescriptorSetLayoutBinding> binds,
                          VkDescriptorSetLayout* out) -> bool {
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        li.bindingCount = uint32_t(binds.size());
        li.pBindings = binds.data();
        CHECK_VK(vkCreateDescriptorSetLayout(device_, &li, nullptr, out), "Loi set layout");
        return true;
    };
    auto alloc = [&](VkDescriptorSetLayout dsl, VkDescriptorSet* out) -> bool {
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool = descPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &dsl;
        CHECK_VK(vkAllocateDescriptorSets(device_, &ai, out), "Loi alloc set");
        return true;
    };

    // LUT pass: b0 = midA, b1 = LUT 3D
    if (!makeLayout({{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
                     {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}},
                    &dslLut_)) return false;
    if (!alloc(dslLut_, &setLut_)) return false;

    // Post-LUT (Task 3.1): b0 = midB, b1 = UBO Layer 3
    if (!makeLayout({{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
                     {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}},
                    &dslPost_)) return false;
    if (!alloc(dslPost_, &setPost_)) return false;

    // Composite: b0 = midC (anh cuoi sau Layer 3) · b1 = watermark (Task E3)
    if (!makeLayout({{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
                     {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}},
                    &dslComp_)) return false;
    if (!alloc(dslComp_, &setComp_)) return false;
    // b1 duoc ghi trong uploadWatermarkRgba (placeholder 1x1 nap o cuoi init)

    // Scope build (compute): b0 = midC (scope soi ANH CUOI — dung cho Skin Tone Line), b1 = SSBO
    if (!makeLayout({{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                     {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}},
                    &dslScopeBuild_)) return false;
    if (!alloc(dslScopeBuild_, &setScopeBuild_)) return false;

    // Scope draw: b1 = SSBO (frag)
    if (!makeLayout({{1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}},
                    &dslScopeDraw_)) return false;
    if (!alloc(dslScopeDraw_, &setScopeDraw_)) return false;

    // Ghi cac set tinh (midA/midB/midC/UBO/SSBO khong doi sau init)
    VkDescriptorImageInfo imA{linearSampler_, midAView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo imB{linearSampler_, midBView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo imC{linearSampler_, midCView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorBufferInfo sb{scopeBuf_, 0, kScopeBufSize};
    VkDescriptorBufferInfo ub{l3Ubo_, 0, sizeof(Layer3Ubo)};
    VkWriteDescriptorSet ws[7]{};
    ws[0] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setLut_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imA, nullptr, nullptr};
    ws[1] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setPost_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imB, nullptr, nullptr};
    ws[2] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setPost_, 1, 0, 1,
             VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &ub, nullptr};
    ws[3] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imC, nullptr, nullptr};
    ws[4] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setScopeBuild_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imC, nullptr, nullptr};
    ws[5] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setScopeBuild_, 1, 0, 1,
             VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &sb, nullptr};
    ws[6] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setScopeDraw_, 1, 0, 1,
             VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nullptr, &sb, nullptr};
    vkUpdateDescriptorSets(device_, 7, ws, 0, nullptr);
    return true;
}

// ============================================================= pipelines ====
VkShaderModule VulkanRenderer::loadShader(const char* assetPath, std::string* err) {
    AAsset* a = AAssetManager_open(assets_, assetPath, AASSET_MODE_BUFFER);
    if (!a) { if (err) *err = std::string("Khong mo duoc asset ") + assetPath; return VK_NULL_HANDLE; }
    const size_t len = size_t(AAsset_getLength(a));
    std::vector<uint32_t> code((len + 3) / 4);
    std::memcpy(code.data(), AAsset_getBuffer(a), len);
    AAsset_close(a);
    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = len;
    smi.pCode = code.data();
    VkShaderModule mod = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device_, &smi, nullptr, &mod) != VK_SUCCESS) {
        if (err) *err = std::string("Loi shader module ") + assetPath;
        return VK_NULL_HANDLE;
    }
    return mod;
}

bool VulkanRenderer::makeGraphicsPipeline(VkShaderModule vs, VkShaderModule fs, VkRenderPass rp,
                                          VkPipelineLayout layout, bool blend,
                                          VkPipeline* out, std::string* err) {
    VkPipelineShaderStageCreateInfo st[2]{};
    st[0].sType = st[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    st[0].stage = VK_SHADER_STAGE_VERTEX_BIT;   st[0].module = vs; st[0].pName = "main";
    st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; st[1].module = fs; st[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{};
    ba.colorWriteMask = 0xF;
    ba.blendEnable = blend ? VK_TRUE : VK_FALSE;
    if (blend) {
        ba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        ba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        ba.colorBlendOp = VK_BLEND_OP_ADD;
        ba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        ba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
        ba.alphaBlendOp = VK_BLEND_OP_ADD;
    }
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &ba;
    const VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2;
    ds.pDynamicStates = dyn;

    VkGraphicsPipelineCreateInfo gpi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gpi.stageCount = 2;      gpi.pStages = st;
    gpi.pVertexInputState = &vin;
    gpi.pInputAssemblyState = &ia;
    gpi.pViewportState = &vp;
    gpi.pRasterizationState = &rs;
    gpi.pMultisampleState = &ms;
    gpi.pColorBlendState = &cb;
    gpi.pDynamicState = &ds;
    gpi.layout = layout;
    gpi.renderPass = rp;
    CHECK_VK(vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &gpi, nullptr, out),
             "Loi graphics pipeline");
    return true;
}

bool VulkanRenderer::createStaticPipelines(std::string* err) {
    vsFullscreen_ = loadShader("shaders/fullscreen.vert.spv", err);
    if (!vsFullscreen_) return false;
    VkShaderModule fsLut  = loadShader("shaders/lut_tetrahedral.frag.spv", err);
    VkShaderModule fsPost = loadShader("shaders/post_lut.frag.spv", err);
    VkShaderModule fsBlit = loadShader("shaders/composite.frag.spv", err);  // Task 4: kem clarity
    VkShaderModule fsScp  = loadShader("shaders/scopes_popup.frag.spv", err);
    VkShaderModule csScp  = loadShader("shaders/scopes_build.comp.spv", err);
    if (!fsLut || !fsPost || !fsBlit || !fsScp || !csScp) return false;

    auto makePl = [&](VkDescriptorSetLayout dsl, VkShaderStageFlags stage, uint32_t pcSize,
                      VkPipelineLayout* out) -> bool {
        VkPushConstantRange pcr{stage, 0, pcSize};
        VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pli.setLayoutCount = 1;
        pli.pSetLayouts = &dsl;
        pli.pushConstantRangeCount = pcSize ? 1u : 0u;
        pli.pPushConstantRanges = pcSize ? &pcr : nullptr;
        CHECK_VK(vkCreatePipelineLayout(device_, &pli, nullptr, out), "Loi pipeline layout");
        return true;
    };
    if (!makePl(dslLut_, VK_SHADER_STAGE_FRAGMENT_BIT, sizeof(LutPushConstants), &plLut_)) return false;
    if (!makePl(dslPost_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, &plPost_)) return false;
    if (!makePl(dslComp_, VK_SHADER_STAGE_FRAGMENT_BIT, sizeof(CompositePC), &plComp_)) return false;
    if (!makePl(dslScopeDraw_, VK_SHADER_STAGE_FRAGMENT_BIT, sizeof(ScopeDrawPC), &plScopeDraw_)) return false;
    if (!makePl(dslScopeBuild_, VK_SHADER_STAGE_COMPUTE_BIT, sizeof(ScopeBuildPC), &plScopeBuild_)) return false;

    if (!makeGraphicsPipeline(vsFullscreen_, fsLut,  rpFp16_, plLut_,       false, &pipeLut_, err)) return false;
    if (!makeGraphicsPipeline(vsFullscreen_, fsPost, rpFp16_, plPost_,      false, &pipePost_, err)) return false;
    if (!makeGraphicsPipeline(vsFullscreen_, fsBlit, rpSwap_, plComp_,      false, &pipeComp_, err)) return false;
    if (!makeGraphicsPipeline(vsFullscreen_, fsScp,  rpSwap_, plScopeDraw_, true,  &pipeScopeDraw_, err)) return false;

    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0,
                 VK_SHADER_STAGE_COMPUTE_BIT, csScp, "main", nullptr};
    cpi.layout = plScopeBuild_;
    CHECK_VK(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &cpi, nullptr, &pipeScopeBuild_),
             "Loi compute pipeline scope");

    vkDestroyShaderModule(device_, fsLut, nullptr);
    vkDestroyShaderModule(device_, fsPost, nullptr);
    vkDestroyShaderModule(device_, fsBlit, nullptr);
    vkDestroyShaderModule(device_, fsScp, nullptr);
    vkDestroyShaderModule(device_, csScp, nullptr);
    return true;
}

// Lazy: set layout CST can IMMUTABLE YCbCr sampler — chi biet sau frame dau.
// Cache theo externalFormat: decoder giu nguyen dinh dang -> tao dung 1 lan.
bool VulkanRenderer::ensureCstPipeline(uint64_t externalFormat, std::string* err) {
    if (pipeCst_ && externalFormat == cachedExternalFormat_) return true;
    destroyCstObjects();   // xoa layout/pipeline/set cu (giu conv/sampler — da tao moi)
    cachedExternalFormat_ = externalFormat;

    VkDescriptorSetLayoutBinding b0{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                                    VK_SHADER_STAGE_FRAGMENT_BIT, &srcSampler_};
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 1;
    li.pBindings = &b0;
    CHECK_VK(vkCreateDescriptorSetLayout(device_, &li, nullptr, &dslCst_), "Loi layout CST");

    VkPushConstantRange pcr{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(Layer1PushConstants)};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &dslCst_;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    CHECK_VK(vkCreatePipelineLayout(device_, &pli, nullptr, &plCst_), "Loi pl CST");

    VkShaderModule fsCst = loadShader("shaders/color_space.frag.spv", err);
    if (!fsCst) return false;
    bool ok = makeGraphicsPipeline(vsFullscreen_, fsCst, rpFp16_, plCst_, false, &pipeCst_, err);
    vkDestroyShaderModule(device_, fsCst, nullptr);
    if (!ok) return false;

    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = descPool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &dslCst_;
    CHECK_VK(vkAllocateDescriptorSets(device_, &ai, &setCst_), "Loi alloc set CST");
    return true;
}

// ================================================== AHardwareBuffer import ==
bool VulkanRenderer::submitDecodedFrame(AHardwareBuffer* buf, std::string* err) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    srcExtent_ = {desc.width, desc.height};

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, &fmtProps};
    CHECK_VK(vkGetAndroidHardwareBufferPropertiesANDROID(device_, buf, &props),
             "Khong doc duoc thuoc tinh AHardwareBuffer");

    // YCbCr conversion + sampler: chi tao lai khi external format DOI
    if (!ycbcrConv_ || fmtProps.externalFormat != cachedExternalFormat_) {
        clearAhbCache();   // view cu tham chieu conversion cu — phai xoa het
        if (srcSampler_) { vkDestroySampler(device_, srcSampler_, nullptr); srcSampler_ = VK_NULL_HANDLE; }
        if (ycbcrConv_)  { vkDestroySamplerYcbcrConversion(device_, ycbcrConv_, nullptr); ycbcrConv_ = VK_NULL_HANDLE; }

        VkExternalFormatANDROID extFmt{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
        extFmt.externalFormat = fmtProps.externalFormat;
        VkSamplerYcbcrConversionCreateInfo yci{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO};
        yci.pNext = &extFmt;
        yci.format = VK_FORMAT_UNDEFINED;
        yci.ycbcrModel = fmtProps.suggestedYcbcrModel;
        yci.ycbcrRange = fmtProps.suggestedYcbcrRange;
        yci.components = fmtProps.samplerYcbcrConversionComponents;
        yci.xChromaOffset = fmtProps.suggestedXChromaOffset;
        yci.yChromaOffset = fmtProps.suggestedYChromaOffset;
        yci.chromaFilter = VK_FILTER_LINEAR;
        CHECK_VK(vkCreateSamplerYcbcrConversion(device_, &yci, nullptr, &ycbcrConv_),
                 "Loi YCbCr conversion");

        VkSamplerYcbcrConversionInfo convInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
        convInfo.conversion = ycbcrConv_;
        VkSamplerCreateInfo smi{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        smi.pNext = &convInfo;
        smi.magFilter = smi.minFilter = VK_FILTER_LINEAR;
        smi.addressModeU = smi.addressModeV = smi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        CHECK_VK(vkCreateSampler(device_, &smi, nullptr, &srcSampler_), "Loi sampler YCbCr");

        if (!ensureCstPipeline(fmtProps.externalFormat, err)) return false;
    }

    // TASK 4.2 — CACHE import theo dia chi buffer: ImageReader xoay ~4 buffer,
    // gap lai buffer da import thi tai su dung ngay (het chi phi import/frame)
    auto hit = ahbCache_.find(buf);
    if (hit != ahbCache_.end()) {
        srcImage_ = hit->second.img;
        srcView_ = hit->second.view;
        srcMemory_ = hit->second.mem;
        VkDescriptorImageInfo im{srcSampler_, srcView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setCst_, 0, 0, 1,
                               VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &im, nullptr, nullptr};
        vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);
        return true;
    }
    srcImage_ = VK_NULL_HANDLE; srcView_ = VK_NULL_HANDLE; srcMemory_ = VK_NULL_HANDLE;

    VkExternalFormatANDROID extFmt{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
    extFmt.externalFormat = fmtProps.externalFormat;
    VkExternalMemoryImageCreateInfo emi{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    emi.pNext = &extFmt;
    emi.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.pNext = &emi;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_UNDEFINED;
    ici.extent = {desc.width, desc.height, 1};
    ici.mipLevels = ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    CHECK_VK(vkCreateImage(device_, &ici, nullptr, &srcImage_), "Loi VkImage tu AHB");

    VkImportAndroidHardwareBufferInfoANDROID imp{
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    imp.buffer = buf;
    VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, &imp};
    ded.image = srcImage_;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &ded};
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = uint32_t(__builtin_ctz(props.memoryTypeBits));
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &srcMemory_), "Loi import bo nho AHB");
    vkBindImageMemory(device_, srcImage_, srcMemory_, 0);

    VkSamplerYcbcrConversionInfo convInfo{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
    convInfo.conversion = ycbcrConv_;
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.pNext = &convInfo;
    vci.image = srcImage_;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = VK_FORMAT_UNDEFINED;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CHECK_VK(vkCreateImageView(device_, &vci, nullptr, &srcView_), "Loi view AHB");

    // Ghi vao cache + giu tham chieu buffer (release o clearAhbCache)
    AHardwareBuffer_acquire(buf);
    ahbCache_[buf] = AhbImport{srcImage_, srcMemory_, srcView_, buf};

    // Cap nhat descriptor CST tro vao frame moi
    VkDescriptorImageInfo im{srcSampler_, srcView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setCst_, 0, 0, 1,
                           VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &im, nullptr, nullptr};
    vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);
    return true;
}

// Ve lai frame hien co (srcView_ van con song trong ahbCache_) — cho phep
// grade tren mot frame dung yen ma preview van cap nhat.
bool VulkanRenderer::redraw(std::string* err) {
    if (!srcImage_) { if (err) *err = "Chua co frame de ve lai"; return false; }
    return renderFrame(err);
}

// TASK 4.2 — giai phong toan bo import cache (giua cac clip / khi doi format /
// sau moi muc trong hang doi batch de RAM/VRAM khong phinh)
void VulkanRenderer::clearAhbCache() {
    if (!device_) return;
    vkDeviceWaitIdle(device_);
    for (auto& kv : ahbCache_) {
        vkDestroyImageView(device_, kv.second.view, nullptr);
        vkDestroyImage(device_, kv.second.img, nullptr);
        vkFreeMemory(device_, kv.second.mem, nullptr);
        AHardwareBuffer_release(kv.second.buf);
    }
    ahbCache_.clear();
    srcImage_ = VK_NULL_HANDLE; srcView_ = VK_NULL_HANDLE; srcMemory_ = VK_NULL_HANDLE;
}

// ================================================================= LUT ======
void VulkanRenderer::destroyLutObjects() {
    if (lutSampler_) vkDestroySampler(device_, lutSampler_, nullptr);
    if (lutView_)    vkDestroyImageView(device_, lutView_, nullptr);
    if (lutImage_)   vkDestroyImage(device_, lutImage_, nullptr);
    if (lutMemory_)  vkFreeMemory(device_, lutMemory_, nullptr);
    lutSampler_ = VK_NULL_HANDLE; lutView_ = VK_NULL_HANDLE;
    lutImage_ = VK_NULL_HANDLE; lutMemory_ = VK_NULL_HANDLE;
}

bool VulkanRenderer::uploadLut(const CubeLut& lut, std::string* err) {
    if (!lut.valid()) { if (err) *err = "LUT khong hop le"; return false; }
    vkDeviceWaitIdle(device_);
    destroyLutObjects();
    lutSize_ = lut.size;

    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = lut.byteSize();
    bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    CHECK_VK(vkCreateBuffer(device_, &bci, nullptr, &staging), "Loi staging");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, staging, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &stagingMem), "Loi bo nho staging");
    vkBindBufferMemory(device_, staging, stagingMem, 0);
    void* mapped = nullptr;
    vkMapMemory(device_, stagingMem, 0, lut.byteSize(), 0, &mapped);
    std::memcpy(mapped, lut.rgba.data(), lut.byteSize());
    vkUnmapMemory(device_, stagingMem);

    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_3D;
    ici.format = VK_FORMAT_R32G32B32A32_SFLOAT;
    ici.extent = {uint32_t(lut.size), uint32_t(lut.size), uint32_t(lut.size)};
    ici.mipLevels = ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    CHECK_VK(vkCreateImage(device_, &ici, nullptr, &lutImage_), "Loi anh LUT 3D");
    vkGetImageMemoryRequirements(device_, lutImage_, &req);
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &lutMemory_), "Loi VRAM LUT");
    vkBindImageMemory(device_, lutImage_, lutMemory_, 0);

    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetFences(device_, 1, &fence_);
    vkBeginCommandBuffer(cmd_, &cbi);
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.image = lutImage_;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = ici.extent;
    vkCmdCopyBufferToImage(cmd_, staging, lutImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    vkEndCommandBuffer(cmd_);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd_;
    vkQueueSubmit(queue_, 1, &si, fence_);
    vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX);
    vkDestroyBuffer(device_, staging, nullptr);
    vkFreeMemory(device_, stagingMem, nullptr);

    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = lutImage_;
    vci.viewType = VK_IMAGE_VIEW_TYPE_3D;
    vci.format = VK_FORMAT_R32G32B32A32_SFLOAT;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CHECK_VK(vkCreateImageView(device_, &vci, nullptr, &lutView_), "Loi view LUT");
    VkSamplerCreateInfo smi{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    smi.magFilter = smi.minFilter = VK_FILTER_NEAREST;   // shader tu noi suy tetrahedral
    smi.addressModeU = smi.addressModeV = smi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    CHECK_VK(vkCreateSampler(device_, &smi, nullptr, &lutSampler_), "Loi sampler LUT");

    VkDescriptorImageInfo im{lutSampler_, lutView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setLut_, 1, 0, 1,
                           VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &im, nullptr, nullptr};
    vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);
    return true;
}

bool VulkanRenderer::uploadIdentityLut(std::string* err) {
    CubeLut id;
    id.size = 2;
    id.rgba = {0,0,0,1, 1,0,0,1, 0,1,0,1, 1,1,0,1, 0,0,1,1, 1,0,1,1, 0,1,1,1, 1,1,1,1};
    return uploadLut(id, err);
}

// ===================== TASK E3 — WATERMARK (logo PNG alpha) =================
void VulkanRenderer::destroyWatermarkObjects() {
    if (wmView_)   { vkDestroyImageView(device_, wmView_, nullptr); wmView_ = VK_NULL_HANDLE; }
    if (wmImage_)  { vkDestroyImage(device_, wmImage_, nullptr); wmImage_ = VK_NULL_HANDLE; }
    if (wmMemory_) { vkFreeMemory(device_, wmMemory_, nullptr); wmMemory_ = VK_NULL_HANDLE; }
}

/** Nap RGBA8 2D len VkImage + ghi descriptor b1 cua composite (staging nhu LUT). */
bool VulkanRenderer::uploadWatermarkRgba(const uint8_t* rgba, uint32_t w, uint32_t h,
                                         std::string* err) {
    vkDeviceWaitIdle(device_);
    destroyWatermarkObjects();
    const VkDeviceSize bytes = VkDeviceSize(w) * h * 4;

    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = bytes;
    bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    CHECK_VK(vkCreateBuffer(device_, &bci, nullptr, &staging), "Loi staging watermark");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, staging, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &stagingMem), "Loi bo nho staging wm");
    vkBindBufferMemory(device_, staging, stagingMem, 0);
    void* mapped = nullptr;
    vkMapMemory(device_, stagingMem, 0, bytes, 0, &mapped);
    std::memcpy(mapped, rgba, size_t(bytes));
    vkUnmapMemory(device_, stagingMem);

    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_R8G8B8A8_UNORM;
    ici.extent = {w, h, 1};
    ici.mipLevels = ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    CHECK_VK(vkCreateImage(device_, &ici, nullptr, &wmImage_), "Loi anh watermark");
    vkGetImageMemoryRequirements(device_, wmImage_, &req);
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CHECK_VK(vkAllocateMemory(device_, &mai, nullptr, &wmMemory_), "Loi VRAM watermark");
    vkBindImageMemory(device_, wmImage_, wmMemory_, 0);

    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetFences(device_, 1, &fence_);
    vkBeginCommandBuffer(cmd_, &cbi);
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.image = wmImage_;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = ici.extent;
    vkCmdCopyBufferToImage(cmd_, staging, wmImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    vkEndCommandBuffer(cmd_);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd_;
    vkQueueSubmit(queue_, 1, &si, fence_);
    vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX);
    vkDestroyBuffer(device_, staging, nullptr);
    vkFreeMemory(device_, stagingMem, nullptr);

    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = wmImage_;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = VK_FORMAT_R8G8B8A8_UNORM;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CHECK_VK(vkCreateImageView(device_, &vci, nullptr, &wmView_), "Loi view watermark");

    VkDescriptorImageInfo im{linearSampler_, wmView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet ws{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp_, 1, 0, 1,
                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &im, nullptr, nullptr};
    vkUpdateDescriptorSets(device_, 1, &ws, 0, nullptr);
    wmW_ = w; wmH_ = h;
    return true;
}

bool VulkanRenderer::setWatermarkImage(const uint8_t* rgba, uint32_t w, uint32_t h,
                                       std::string* err) {
    if (!rgba || w == 0 || h == 0) { if (err) *err = "Watermark rong"; return false; }
    return uploadWatermarkRgba(rgba, w, h, err);
}

// ============================================================ renderFrame ===
bool VulkanRenderer::renderFrame(std::string* err) {
    if (!srcImage_ || !pipeCst_) { if (err) *err = "Chua co frame decode"; return false; }
    vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &fence_);

    uint32_t imgIdx = 0;
    CHECK_VK(vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                   semAcquire_, VK_NULL_HANDLE, &imgIdx),
             "Loi acquire swapchain image");

    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd_, &cbi);

    const VkViewport vpFull{0.f, 0.f, float(swapExtent_.width), float(swapExtent_.height), 0.f, 1.f};
    const VkRect2D scFull{{0, 0}, swapExtent_};
    const VkClearValue clear{{{0.f, 0.f, 0.f, 1.f}}};
    const int effScopeMode = exportActive_ ? 0 : scopeMode_;   // export: khong ve scope

    // 0. Acquire frame decode tu queue ngoai (decoder) + xoa scope bins
    VkImageMemoryBarrier acq{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    acq.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    acq.dstQueueFamilyIndex = queueFamily_;
    acq.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    acq.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    acq.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acq.image = srcImage_;
    acq.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &acq);
    if (effScopeMode > 0) {
        vkCmdFillBuffer(cmd_, scopeBuf_, 0, kScopeBufSize, 0u);
        VkBufferMemoryBarrier bb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        bb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        bb.srcQueueFamilyIndex = bb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bb.buffer = scopeBuf_;
        bb.size = kScopeBufSize;
        vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bb, 0, nullptr);
    }

    // Push constants Layer 1: preset hoac override tu slider; eye-toggle qua layerOn.
    // Before/After (Task 5.3): bypass_ = true -> chi CST, tat het hieu ung 3 layer.
    Layer1PushConstants l1 = useOverride_
        ? MakeLayer1PC(override_, antiGreen_, layerOn_[0] && !bypass_)
        : MakeLayer1PC(kPresets[presetIndex_], antiGreen_, layerOn_[0] && !bypass_);
    LutPushConstants l2{};
    l2.intensity = (layerOn_[1] && !bypass_)
        ? (lutOverride_ >= 0.f ? lutOverride_ : kPresets[presetIndex_].lut_engine.intensity)
        : 0.f;
    l2.lutSize = float(lutSize_ ? lutSize_ : 2);

    auto fullscreenPass = [&](VkRenderPass rp, VkFramebuffer fb, VkPipeline pipe,
                              VkPipelineLayout pl, VkDescriptorSet set,
                              const void* pc, uint32_t pcSize) {
        VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rbi.renderPass = rp;
        rbi.framebuffer = fb;
        rbi.renderArea = scFull;
        rbi.clearValueCount = 1;
        rbi.pClearValues = &clear;
        vkCmdBeginRenderPass(cmd_, &rbi, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        vkCmdSetViewport(cmd_, 0, 1, &vpFull);
        vkCmdSetScissor(cmd_, 0, 1, &scFull);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_GRAPHICS, pl, 0, 1, &set, 0, nullptr);
        if (pcSize) vkCmdPushConstants(cmd_, pl, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcSize, pc);
        vkCmdDraw(cmd_, 3, 1, 0, 0);
    };

    // Cap nhat UBO Layer 3 (preset hoac override slider; keyframing cung ghi qua day)
    {
        const PostLutParams& p3 = useOverride3_ ? override3_ : kPresets[presetIndex_].post_lut;
        Layer3Ubo u{};
        for (int i = 0; i < kHSLBandCount; i++) {
            u.hsl[i][0] = p3.hsl[i].hue_deg;
            u.hsl[i][1] = p3.hsl[i].saturation;
            u.hsl[i][2] = p3.hsl[i].luma;
        }
        u.misc[0] = p3.global_saturation;
        u.misc[1] = p3.skin_tone_protection ? 1.f : 0.f;
        u.misc[2] = (layerOn_[2] && !bypass_) ? 1.f : 0.f;
        u.shadowTint[0] = p3.shadow_tint_rgb[0];
        u.shadowTint[1] = p3.shadow_tint_rgb[1];
        u.shadowTint[2] = p3.shadow_tint_rgb[2];
        // TASK S2 — Skin-Tone Lock Mask (bypass Before/After tat luon mask view)
        const SkinMaskParams& sm = p3.skin_mask;
        u.skinMask[0] = sm.target_hue_deg;
        u.skinMask[1] = sm.tolerance_deg;
        u.skinMask[2] = sm.feather_deg;
        u.skinMask[3] = sm.strength;
        u.skinMask2[0] = (sm.enabled && !bypass_) ? 1.f : 0.f;
        u.skinMask2[1] = (sm.mask_view && !bypass_) ? 1.f : 0.f;
        u.skinMask2[2] = sm.sat_gate_lo;
        u.skinMask2[3] = sm.val_gate_lo;
        std::memcpy(l3UboMapped_, &u, sizeof(u));
    }

    // 1. Pass CST/Layer1 -> midA
    fullscreenPass(rpFp16_, fbA_, pipeCst_, plCst_, setCst_, &l1, sizeof(l1));
    vkCmdEndRenderPass(cmd_);
    // 2. Pass LUT -> midB
    fullscreenPass(rpFp16_, fbB_, pipeLut_, plLut_, setLut_, &l2, sizeof(l2));
    vkCmdEndRenderPass(cmd_);
    // 3. Pass Post-LUT (HSL Layer 3) -> midC  [Task 3.1]
    fullscreenPass(rpFp16_, fbC_, pipePost_, plPost_, setPost_, nullptr, 0);
    vkCmdEndRenderPass(cmd_);

    // 4. Compute scope bins tu midC (anh cuoi)
    if (effScopeMode > 0) {
        ScopeBuildPC spc{int32_t(swapExtent_.width), int32_t(swapExtent_.height), scopeMode_, 0};
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeScopeBuild_);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, plScopeBuild_, 0, 1,
                                &setScopeBuild_, 0, nullptr);
        vkCmdPushConstants(cmd_, plScopeBuild_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(spc), &spc);
        vkCmdDispatch(cmd_, (swapExtent_.width / 4 + 15) / 16, (swapExtent_.height / 4 + 15) / 16, 1);
        VkBufferMemoryBarrier bb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        bb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        bb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bb.srcQueueFamilyIndex = bb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bb.buffer = scopeBuf_;
        bb.size = kScopeBufSize;
        vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 1, &bb, 0, nullptr);
    }

    // 5. Composite (+ clarity + watermark E3) -> swapchain (+ overlay scope)
    CompositePC cpc{};
    cpc.clarity = bypass_ ? 0.f : clarity_;
    cpc.texelW = 1.f / float(swapExtent_.width);
    cpc.texelH = 1.f / float(swapExtent_.height);
    // E3 — logo goc phai-duoi: rong 20% khung, le 3% chieu cao; wmW_>1 = da nap logo that
    if (wmEnabled_ && !bypass_ && wmW_ > 1) {
        const float W = float(swapExtent_.width), H = float(swapExtent_.height);
        const float wPx = 0.20f * W;
        const float hPx = wPx * float(wmH_) / float(wmW_);
        const float margin = 0.03f * H;
        cpc.wmOn = 1.f;
        cpc.wmRect[0] = (W - wPx - margin) / W;
        cpc.wmRect[1] = (H - hPx - margin) / H;
        cpc.wmRect[2] = wPx / W;
        cpc.wmRect[3] = hPx / H;
    }
    fullscreenPass(rpSwap_, fbSwap_[imgIdx], pipeComp_, plComp_, setComp_, &cpc, sizeof(cpc));
    if (effScopeMode > 0) {
        const float W = float(swapExtent_.width), H = float(swapExtent_.height);
        float side = scopeSize_ * (W < H ? W : H);
        float x = scopeCx_ * W - side * 0.5f, y = scopeCy_ * H - side * 0.5f;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x + side > W) x = W - side;
        if (y + side > H) y = H - side;
        VkViewport vpS{x, y, side, side, 0.f, 1.f};
        VkRect2D scS{{int32_t(x), int32_t(y)}, {uint32_t(side), uint32_t(side)}};
        ScopeDrawPC dpc{float(scopeMode_), scopeOpacity_, 0.05f, 0.f};
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeScopeDraw_);
        vkCmdSetViewport(cmd_, 0, 1, &vpS);
        vkCmdSetScissor(cmd_, 0, 1, &scS);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_GRAPHICS, plScopeDraw_, 0, 1,
                                &setScopeDraw_, 0, nullptr);
        vkCmdPushConstants(cmd_, plScopeDraw_, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(dpc), &dpc);
        vkCmdDraw(cmd_, 3, 1, 0, 0);
    }
    vkCmdEndRenderPass(cmd_);
    vkEndCommandBuffer(cmd_);

    const VkPipelineStageFlags wait = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;   si.pWaitSemaphores = &semAcquire_;
    si.pWaitDstStageMask = &wait;
    si.commandBufferCount = 1;   si.pCommandBuffers = &cmd_;
    si.signalSemaphoreCount = 1; si.pSignalSemaphores = &semRender_;
    CHECK_VK(vkQueueSubmit(queue_, 1, &si, fence_), "Loi submit");

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &semRender_;
    pi.swapchainCount = 1;     pi.pSwapchains = &swapchain_;
    pi.pImageIndices = &imgIdx;
    CHECK_VK(vkQueuePresentKHR(queue_, &pi), "Loi present");
    return true;
}

// ============================================================== setters =====
void VulkanRenderer::setPreset(int index) {
    presetIndex_ = index < 0 ? 0 : (index >= kPresetCount ? kPresetCount - 1 : index);
    useOverride_ = false;                       // preset moi -> bo override slider
    useOverride3_ = false;
}
void VulkanRenderer::setLayer1Params(const PreLutParams& p) { override_ = p; useOverride_ = true; }
void VulkanRenderer::setLayer3Params(const PostLutParams& p) { override3_ = p; useOverride3_ = true; }
void VulkanRenderer::setClarity(float v)              { clarity_ = v; }
void VulkanRenderer::setBypassGrade(bool on)          { bypass_ = on; }
void VulkanRenderer::setAntiGreen(float s)            { antiGreen_ = s; }
void VulkanRenderer::setLutIntensityOverride(float v) { lutOverride_ = v; }
void VulkanRenderer::setLayerVisibility(bool a, bool b, bool c) { layerOn_[0]=a; layerOn_[1]=b; layerOn_[2]=c; }
void VulkanRenderer::setScopeConfig(int mode, float cx, float cy, float size, float opacity) {
    scopeMode_ = mode; scopeCx_ = cx; scopeCy_ = cy;
    scopeSize_ = size < 0.10f ? 0.10f : (size > 0.60f ? 0.60f : size);
    scopeOpacity_ = opacity;
}

// ============================================================== teardown ====
uint32_t VulkanRenderer::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mem{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &mem);
    for (uint32_t i = 0; i < mem.memoryTypeCount; i++)
        if ((typeBits & (1u << i)) && (mem.memoryTypes[i].propertyFlags & props) == props)
            return i;
    return 0;
}

void VulkanRenderer::destroyCstObjects() {
    if (setCst_)  { vkFreeDescriptorSets(device_, descPool_, 1, &setCst_); setCst_ = VK_NULL_HANDLE; }
    if (pipeCst_) { vkDestroyPipeline(device_, pipeCst_, nullptr); pipeCst_ = VK_NULL_HANDLE; }
    if (plCst_)   { vkDestroyPipelineLayout(device_, plCst_, nullptr); plCst_ = VK_NULL_HANDLE; }
    if (dslCst_)  { vkDestroyDescriptorSetLayout(device_, dslCst_, nullptr); dslCst_ = VK_NULL_HANDLE; }
}

void VulkanRenderer::destroyMidTargets() {
    if (fbA_) { vkDestroyFramebuffer(device_, fbA_, nullptr); fbA_ = VK_NULL_HANDLE; }
    if (fbB_) { vkDestroyFramebuffer(device_, fbB_, nullptr); fbB_ = VK_NULL_HANDLE; }
    if (fbC_) { vkDestroyFramebuffer(device_, fbC_, nullptr); fbC_ = VK_NULL_HANDLE; }
    if (midAView_) { vkDestroyImageView(device_, midAView_, nullptr); midAView_ = VK_NULL_HANDLE; }
    if (midBView_) { vkDestroyImageView(device_, midBView_, nullptr); midBView_ = VK_NULL_HANDLE; }
    if (midCView_) { vkDestroyImageView(device_, midCView_, nullptr); midCView_ = VK_NULL_HANDLE; }
    if (midA_)     { vkDestroyImage(device_, midA_, nullptr); midA_ = VK_NULL_HANDLE; }
    if (midB_)     { vkDestroyImage(device_, midB_, nullptr); midB_ = VK_NULL_HANDLE; }
    if (midC_)     { vkDestroyImage(device_, midC_, nullptr); midC_ = VK_NULL_HANDLE; }
    if (midAMem_)  { vkFreeMemory(device_, midAMem_, nullptr); midAMem_ = VK_NULL_HANDLE; }
    if (midBMem_)  { vkFreeMemory(device_, midBMem_, nullptr); midBMem_ = VK_NULL_HANDLE; }
    if (midCMem_)  { vkFreeMemory(device_, midCMem_, nullptr); midCMem_ = VK_NULL_HANDLE; }
}

void VulkanRenderer::destroySwapSideObjects() {
    for (auto f : fbSwap_) vkDestroyFramebuffer(device_, f, nullptr);
    fbSwap_.clear();
    for (auto v : swapViews_) vkDestroyImageView(device_, v, nullptr);
    swapViews_.clear();
    swapImages_.clear();
    if (swapchain_) { vkDestroySwapchainKHR(device_, swapchain_, nullptr); swapchain_ = VK_NULL_HANDLE; }
}

void VulkanRenderer::refreshMidDescriptors() {
    VkDescriptorImageInfo imA{linearSampler_, midAView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo imB{linearSampler_, midBView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo imC{linearSampler_, midCView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet ws[4]{};
    ws[0] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setLut_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imA, nullptr, nullptr};
    ws[1] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setPost_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imB, nullptr, nullptr};
    ws[2] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imC, nullptr, nullptr};
    ws[3] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setScopeBuild_, 0, 0, 1,
             VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imC, nullptr, nullptr};
    vkUpdateDescriptorSets(device_, 4, ws, 0, nullptr);
}

void VulkanRenderer::onSurfaceResized(uint32_t w, uint32_t h) {
    if (!device_ || exportActive_) return;
    vkDeviceWaitIdle(device_);
    destroySwapSideObjects();
    destroyMidTargets();
    std::string err;
    if (createSwapchain(w, h, &err) && createIntermediateTargets(&err) && createFramebuffers(&err))
        refreshMidDescriptors();
    else
        LOGE("Resize that bai: %s", err.c_str());
}

// ========================= TASK 4.1 — EXPORT MODE ===========================
// Stash swapchain preview, tao swapchain moi tren ENCODER INPUT SURFACE dung
// do phan giai clip; mid targets tao lai theo do phan giai do. endExport khoi
// phuc nguyen trang. Preview PHAI dung (engine released) truoc khi goi.
bool VulkanRenderer::beginExport(ANativeWindow* encoderSurface, uint32_t w, uint32_t h,
                                 std::string* err) {
    if (exportActive_) { if (err) *err = "Dang export"; return false; }
    vkDeviceWaitIdle(device_);

    bkSurface_ = surface_;         surface_ = VK_NULL_HANDLE;
    bkSwapchain_ = swapchain_;     swapchain_ = VK_NULL_HANDLE;
    bkSwapImages_ = std::move(swapImages_);
    bkSwapViews_ = std::move(swapViews_);
    bkFbSwap_ = std::move(fbSwap_);
    bkExtent_ = swapExtent_;
    swapImages_.clear(); swapViews_.clear(); fbSwap_.clear();

    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = encoderSurface;
    CHECK_VK(vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_),
             "Khong tao duoc surface encoder");
    if (!createSwapchain(w, h, err)) return false;
    destroyMidTargets();
    if (!createIntermediateTargets(err)) return false;   // mid o do phan giai export
    if (!createFramebuffers(err)) return false;
    refreshMidDescriptors();
    clearAhbCache();                                     // clip moi — cache cu vo nghia
    exportActive_ = true;
    return true;
}

bool VulkanRenderer::renderExportFrame(std::string* err) {
    if (!exportActive_) { if (err) *err = "Chua beginExport"; return false; }
    return renderFrame(err);   // members dang tro vao export target; scope tu tat
}

void VulkanRenderer::endExport() {
    if (!exportActive_ || !device_) return;
    vkDeviceWaitIdle(device_);
    clearAhbCache();                                     // Task 4.2: tra VRAM sau moi clip
    destroySwapSideObjects();
    destroyMidTargets();
    if (surface_) { vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }

    surface_ = bkSurface_;       bkSurface_ = VK_NULL_HANDLE;
    swapchain_ = bkSwapchain_;   bkSwapchain_ = VK_NULL_HANDLE;
    swapImages_ = std::move(bkSwapImages_);
    swapViews_ = std::move(bkSwapViews_);
    fbSwap_ = std::move(bkFbSwap_);
    swapExtent_ = bkExtent_;
    std::string err;
    if (createIntermediateTargets(&err) && createMidFramebuffers(&err)) refreshMidDescriptors();
    else LOGE("endExport: khoi phuc mid targets loi: %s", err.c_str());
    exportActive_ = false;
}

void VulkanRenderer::destroy() {
    if (!device_) {
        if (instance_) { vkDestroyInstance(instance_, nullptr); instance_ = VK_NULL_HANDLE; }
        return;
    }
    vkDeviceWaitIdle(device_);
    auto dv = device_;
    if (exportActive_) endExport();
    clearAhbCache();                 // srcImage_/srcView_ la non-owning — cache so huu
    destroyCstObjects();
    destroyLutObjects();
    destroyWatermarkObjects();       // Task E3
    if (srcSampler_) vkDestroySampler(dv, srcSampler_, nullptr);
    if (ycbcrConv_)  vkDestroySamplerYcbcrConversion(dv, ycbcrConv_, nullptr);
    if (l3UboMapped_) { vkUnmapMemory(dv, l3UboMem_); l3UboMapped_ = nullptr; }
    if (l3Ubo_)    vkDestroyBuffer(dv, l3Ubo_, nullptr);
    if (l3UboMem_) vkFreeMemory(dv, l3UboMem_, nullptr);
    if (pipeLut_)        vkDestroyPipeline(dv, pipeLut_, nullptr);
    if (pipePost_)       vkDestroyPipeline(dv, pipePost_, nullptr);
    if (pipeComp_)       vkDestroyPipeline(dv, pipeComp_, nullptr);
    if (pipeScopeDraw_)  vkDestroyPipeline(dv, pipeScopeDraw_, nullptr);
    if (pipeScopeBuild_) vkDestroyPipeline(dv, pipeScopeBuild_, nullptr);
    if (plLut_)        vkDestroyPipelineLayout(dv, plLut_, nullptr);
    if (plPost_)       vkDestroyPipelineLayout(dv, plPost_, nullptr);
    if (plComp_)       vkDestroyPipelineLayout(dv, plComp_, nullptr);
    if (plScopeDraw_)  vkDestroyPipelineLayout(dv, plScopeDraw_, nullptr);
    if (plScopeBuild_) vkDestroyPipelineLayout(dv, plScopeBuild_, nullptr);
    if (dslLut_)        vkDestroyDescriptorSetLayout(dv, dslLut_, nullptr);
    if (dslPost_)       vkDestroyDescriptorSetLayout(dv, dslPost_, nullptr);
    if (dslComp_)       vkDestroyDescriptorSetLayout(dv, dslComp_, nullptr);
    if (dslScopeBuild_) vkDestroyDescriptorSetLayout(dv, dslScopeBuild_, nullptr);
    if (dslScopeDraw_)  vkDestroyDescriptorSetLayout(dv, dslScopeDraw_, nullptr);
    if (descPool_)      vkDestroyDescriptorPool(dv, descPool_, nullptr);
    if (vsFullscreen_)  vkDestroyShaderModule(dv, vsFullscreen_, nullptr);
    if (scopeBuf_) vkDestroyBuffer(dv, scopeBuf_, nullptr);
    if (scopeMem_) vkFreeMemory(dv, scopeMem_, nullptr);
    if (linearSampler_) vkDestroySampler(dv, linearSampler_, nullptr);
    if (rpFp16_) vkDestroyRenderPass(dv, rpFp16_, nullptr);
    if (rpSwap_) vkDestroyRenderPass(dv, rpSwap_, nullptr);
    destroySwapSideObjects();
    destroyMidTargets();
    if (fence_)      vkDestroyFence(dv, fence_, nullptr);
    if (semAcquire_) vkDestroySemaphore(dv, semAcquire_, nullptr);
    if (semRender_)  vkDestroySemaphore(dv, semRender_, nullptr);
    if (cmdPool_)    vkDestroyCommandPool(dv, cmdPool_, nullptr);
    vkDestroyDevice(dv, nullptr);
    device_ = VK_NULL_HANDLE;
    if (surface_)  vkDestroySurfaceKHR(instance_, surface_, nullptr);
    if (instance_) vkDestroyInstance(instance_, nullptr);
    surface_ = VK_NULL_HANDLE;
    instance_ = VK_NULL_HANDLE;
}

}  // namespace fdc
