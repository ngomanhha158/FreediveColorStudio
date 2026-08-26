// ============================================================================
//  TASK 1.1 + 2.2 — VULKAN RENDERER · FreediveColorStudio (TUAN 2: pipeline day du)
//  Kien truc 3 pass + scopes:
//    Pass 1  color_space.frag    : D-Log M -> Rec.709 + Layer 1  -> midA (fp16)
//    Pass 2  lut_tetrahedral.frag: LUT .cube tetrahedral + Mix   -> midB (fp16)
//    Compute scopes_build.comp   : dem bin Vectorscope/Waveform tu midB -> SSBO
//    Pass 3  blit.frag + scopes_popup.frag: midB -> swapchain + overlay scope
// ============================================================================
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include "layer1_push_constants.h"   // fdc::Layer1PushConstants (48B)
#include "lut_parser.h"              // fdc::CubeLut

struct ANativeWindow;
struct AHardwareBuffer;
struct AAssetManager;

namespace fdc {

struct alignas(16) LutPushConstants {        // pass 2 — khop lut_tetrahedral.frag
    float intensity; float lutSize; float _pad[2];
};
static_assert(sizeof(LutPushConstants) == 16, "LutPC phai 16 byte");

struct alignas(16) ScopeBuildPC {            // compute — khop scopes_build.comp
    int32_t width; int32_t height; int32_t mode; int32_t _pad;
};
struct alignas(16) CompositePC {             // TASK 4 + E3 — khop composite.frag (32B)
    float clarity; float texelW; float texelH; float wmOn;
    float wmRect[4];                          // E3: (x, y, w, h) UV goc phai-duoi
};
static_assert(sizeof(CompositePC) == 32, "CompositePC phai 32 byte");
struct alignas(16) ScopeDrawPC {             // overlay — khop scopes_popup.frag
    float mode; float opacity; float gain; float _pad;
};

// TASK 3.1 + S2 — UBO Layer 3 (std140, 176B) — khop block Layer3Ubo trong post_lut.frag.
// Keyframing ghi tham so noi suy vao day moi frame.
struct alignas(16) Layer3Ubo {
    float hsl[7][4];     // (hueDeg, sat, luma, 0) theo thu tu R/O/Y/G/C/B/M
    float misc[4];       // (globalSat, skinProtect, layerOn, 0)
    float shadowTint[4]; // (r, g, b, 0)
    float skinMask[4];   // S2: (targetHueDeg, toleranceDeg, featherDeg, strength)
    float skinMask2[4];  // S2: (enable, maskView, satGateLo, valGateLo)
};
static_assert(sizeof(Layer3Ubo) == 176, "Layer3Ubo phai 176 byte (std140)");

class VulkanRenderer {
public:
    VulkanRenderer() = default;
    ~VulkanRenderer() { destroy(); }
    VulkanRenderer(const VulkanRenderer&) = delete;
    VulkanRenderer& operator=(const VulkanRenderer&) = delete;

    bool init(ANativeWindow* window, AAssetManager* assetMgr, std::string* err);
    void onSurfaceResized(uint32_t width, uint32_t height);
    void destroy();

    bool submitDecodedFrame(AHardwareBuffer* buffer, std::string* err);
    bool uploadLut(const CubeLut& lut, std::string* err);

    // ---- TASK 4.1 — EXPORT MODE (render offscreen vao encoder surface) ----
    // Preview bi tam dung; swapchain preview duoc stash va khoi phuc o endExport.
    bool beginExport(ANativeWindow* encoderSurface, uint32_t w, uint32_t h, std::string* err);
    bool renderExportFrame(std::string* err);     // nhu renderFrame, khong ve scope
    void endExport();

    // TASK 4.2 — xoa cache import AHardwareBuffer (goi giua cac clip trong batch)
    void clearAhbCache();

    // ---- Tham so grade (UI -> JNI) ----
    void setPreset(int index);                        // 0..4, xoa override
    void setLayer1Params(const PreLutParams& p);      // slider ghi de preset (Task 2.3)
    void setLayer3Params(const PostLutParams& p);     // HSL/globalSat/... ghi de (Task 3.1)
    void setClarity(float v);                          // Task 4 — unsharp luma o composite
    void setBypassGrade(bool on);                      // Task 5.3 — Before/After nhan giu

    // ---- TASK E3 — Watermark PNG alpha (goc phai-duoi, spec 4.3.3) ----
    // rgba: pixel RGBA8 premul-khong (thang), w*h*4 byte. Thay the logo cu.
    bool setWatermarkImage(const uint8_t* rgba, uint32_t w, uint32_t h, std::string* err);
    void setWatermarkEnabled(bool on) { wmEnabled_ = on; }
    void setAntiGreen(float strength);
    void setLutIntensityOverride(float v);            // <0 = theo preset
    void setLayerVisibility(bool l1, bool l2, bool l3);

    // ---- Floating Scopes (Task 2.4) ----
    // mode: 0 tat · 1 Vectorscope · 2 Waveform; cx,cy,size chuan hoa 0..1
    void setScopeConfig(int mode, float cx, float cy, float size, float opacity);

    bool renderFrame(std::string* err);

private:
    // -- khoi tao --
    bool createInstance(std::string* err);
    bool pickPhysicalDevice(std::string* err);
    bool createDevice(std::string* err);
    bool createSwapchain(uint32_t w, uint32_t h, std::string* err);
    bool createIntermediateTargets(std::string* err);       // midA + midB fp16
    bool createRenderPasses(std::string* err);
    bool createFramebuffers(std::string* err);
    bool createScopeBuffer(std::string* err);
    bool createDescriptors(std::string* err);               // pool + layout/set tinh
    bool createStaticPipelines(std::string* err);           // LUT, composite, scopes
    bool ensureCstPipeline(uint64_t externalFormat, std::string* err);  // lazy YCbCr
    bool uploadIdentityLut(std::string* err);               // LUT 2^3 no-op luc dau

    bool createFp16Image(VkImage& img, VkDeviceMemory& mem, VkImageView& view, std::string* err);
    bool makeGraphicsPipeline(VkShaderModule vs, VkShaderModule fs, VkRenderPass rp,
                              VkPipelineLayout layout, bool blend,
                              VkPipeline* out, std::string* err);
    VkShaderModule loadShader(const char* assetPath, std::string* err);
    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props);
    bool createMidFramebuffers(std::string* err);      // fbA/B/C (tach de export dung lai)
    void refreshMidDescriptors();                      // tro descriptor vao mid moi
    void destroyMidTargets();                          // fbA/B/C + midA/B/C
    void destroySwapSideObjects();                     // fbSwap + swapViews + swapchain
    void destroyCstObjects();
    void destroyLutObjects();

    // -- Vulkan core --
    VkInstance       instance_       = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice         device_         = VK_NULL_HANDLE;
    VkQueue          queue_          = VK_NULL_HANDLE;
    uint32_t         queueFamily_    = 0;
    VkSurfaceKHR     surface_        = VK_NULL_HANDLE;
    VkSwapchainKHR   swapchain_      = VK_NULL_HANDLE;
    VkFormat         swapFormat_     = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D       swapExtent_     = {0, 0};
    std::vector<VkImage>       swapImages_;
    std::vector<VkImageView>   swapViews_;
    std::vector<VkFramebuffer> fbSwap_;

    // -- target trung gian fp16 (A: sau L1 · B: sau L2 · C: sau L3 = anh cuoi) --
    VkImage midA_ = VK_NULL_HANDLE, midB_ = VK_NULL_HANDLE, midC_ = VK_NULL_HANDLE;
    VkDeviceMemory midAMem_ = VK_NULL_HANDLE, midBMem_ = VK_NULL_HANDLE, midCMem_ = VK_NULL_HANDLE;
    VkImageView midAView_ = VK_NULL_HANDLE, midBView_ = VK_NULL_HANDLE, midCView_ = VK_NULL_HANDLE;
    VkFramebuffer fbA_ = VK_NULL_HANDLE, fbB_ = VK_NULL_HANDLE, fbC_ = VK_NULL_HANDLE;
    VkSampler linearSampler_ = VK_NULL_HANDLE;

    // -- UBO Layer 3 (host-visible, map thuong truc) --
    VkBuffer l3Ubo_ = VK_NULL_HANDLE; VkDeviceMemory l3UboMem_ = VK_NULL_HANDLE;
    void* l3UboMapped_ = nullptr;

    // -- frame decode: CACHE import theo dia chi AHardwareBuffer (Task 4.2) --
    // ImageReader xoay ~4 buffer nen import 1 lan/buffer roi tai su dung;
    // handle "hien tai" (srcImage_/srcView_) chi TRO vao cache, khong so huu.
    struct AhbImport { VkImage img; VkDeviceMemory mem; VkImageView view; AHardwareBuffer* buf; };
    std::unordered_map<AHardwareBuffer*, AhbImport> ahbCache_;
    VkImage        srcImage_  = VK_NULL_HANDLE;   // non-owning (tru khi cache rong)
    VkDeviceMemory srcMemory_ = VK_NULL_HANDLE;
    VkImageView    srcView_   = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion ycbcrConv_ = VK_NULL_HANDLE;
    VkSampler      srcSampler_ = VK_NULL_HANDLE;
    uint64_t       cachedExternalFormat_ = 0;
    VkExtent2D     srcExtent_ = {0, 0};

    // -- export mode (Task 4.1): stash preview de khoi phuc --
    bool exportActive_ = false;
    VkSurfaceKHR   bkSurface_ = VK_NULL_HANDLE;
    VkSwapchainKHR bkSwapchain_ = VK_NULL_HANDLE;
    std::vector<VkImage>       bkSwapImages_;
    std::vector<VkImageView>   bkSwapViews_;
    std::vector<VkFramebuffer> bkFbSwap_;
    VkExtent2D     bkExtent_ = {0, 0};

    // -- LUT 3D --
    VkImage lutImage_ = VK_NULL_HANDLE; VkDeviceMemory lutMemory_ = VK_NULL_HANDLE;
    VkImageView lutView_ = VK_NULL_HANDLE; VkSampler lutSampler_ = VK_NULL_HANDLE;
    int lutSize_ = 0;

    // -- TASK E3: watermark (logo RGBA8, binding 1 cua composite) --
    bool uploadWatermarkRgba(const uint8_t* rgba, uint32_t w, uint32_t h, std::string* err);
    void destroyWatermarkObjects();
    VkImage wmImage_ = VK_NULL_HANDLE; VkDeviceMemory wmMemory_ = VK_NULL_HANDLE;
    VkImageView wmView_ = VK_NULL_HANDLE;
    uint32_t wmW_ = 0, wmH_ = 0;                       // 0 = chua nap logo (placeholder 1x1)
    bool wmEnabled_ = false;

    // -- scope SSBO (vec 256x256 + wf 256x256, uint) --
    VkBuffer scopeBuf_ = VK_NULL_HANDLE; VkDeviceMemory scopeMem_ = VK_NULL_HANDLE;
    static constexpr VkDeviceSize kScopeBufSize = 2u * 256u * 256u * sizeof(uint32_t);

    // -- render pass / framebuffer --
    VkRenderPass rpFp16_ = VK_NULL_HANDLE, rpSwap_ = VK_NULL_HANDLE;

    // -- descriptor --
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout dslCst_ = VK_NULL_HANDLE, dslLut_ = VK_NULL_HANDLE,
                          dslPost_ = VK_NULL_HANDLE,
                          dslComp_ = VK_NULL_HANDLE, dslScopeBuild_ = VK_NULL_HANDLE,
                          dslScopeDraw_ = VK_NULL_HANDLE;
    VkDescriptorSet setCst_ = VK_NULL_HANDLE, setLut_ = VK_NULL_HANDLE,
                    setPost_ = VK_NULL_HANDLE,
                    setComp_ = VK_NULL_HANDLE, setScopeBuild_ = VK_NULL_HANDLE,
                    setScopeDraw_ = VK_NULL_HANDLE;

    // -- pipeline --
    VkPipelineLayout plCst_ = VK_NULL_HANDLE, plLut_ = VK_NULL_HANDLE,
                     plPost_ = VK_NULL_HANDLE,
                     plComp_ = VK_NULL_HANDLE, plScopeBuild_ = VK_NULL_HANDLE,
                     plScopeDraw_ = VK_NULL_HANDLE;
    VkPipeline pipeCst_ = VK_NULL_HANDLE, pipeLut_ = VK_NULL_HANDLE,
               pipePost_ = VK_NULL_HANDLE,
               pipeComp_ = VK_NULL_HANDLE, pipeScopeBuild_ = VK_NULL_HANDLE,
               pipeScopeDraw_ = VK_NULL_HANDLE;
    VkShaderModule vsFullscreen_ = VK_NULL_HANDLE;   // dung chung, giu den destroy

    // -- dong bo --
    VkCommandPool cmdPool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkSemaphore semAcquire_ = VK_NULL_HANDLE, semRender_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;

    AAssetManager* assets_ = nullptr;

    // -- trang thai grade --
    int           presetIndex_ = 4;
    bool          useOverride_ = false;
    PreLutParams  override_{};
    bool          useOverride3_ = false;
    PostLutParams override3_{};
    float         antiGreen_   = 0.f;
    float         lutOverride_ = -1.f;
    float         clarity_     = 0.f;
    bool          bypass_      = false;   // Before/After: true = chi CST, tat het hieu ung
    bool          layerOn_[3]  = {true, true, true};

    // -- trang thai scope --
    int   scopeMode_   = 0;
    float scopeCx_ = 0.80f, scopeCy_ = 0.16f, scopeSize_ = 0.30f;
    float scopeOpacity_ = 1.0f;
};

}  // namespace fdc
